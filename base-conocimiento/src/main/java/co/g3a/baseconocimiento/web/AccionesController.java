package co.g3a.baseconocimiento.web;

import co.g3a.baseconocimiento.acciones.Acciones;
import co.g3a.baseconocimiento.acciones.Acciones.CoberturaDocumento;
import co.g3a.baseconocimiento.acciones.Acciones.EventoAccion;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion;
import co.g3a.baseconocimiento.acciones.Acciones.Limites;
import co.g3a.baseconocimiento.acciones.Acciones.ResultadoDeAccion;
import co.g3a.baseconocimiento.acciones.Acciones.TextoTraducido;
import co.g3a.baseconocimiento.acciones.Acciones.Tipo;
import co.g3a.baseconocimiento.acciones.Acciones.TraduccionDeDocumentos;
import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * REST y SSE de las acciones sobre los documentos tildados en la barra lateral (issue #38):
 * resumir, sintetizar, preguntas, ideas y traducir, mas el traductor del chat.
 *
 * <p>Solo depende de {@link Acciones} y de {@code compartido} — la segunda puerta que {@code
 * ArquitecturaTest} deja cruzar a los adaptadores, al lado de {@code Consultar}. Mismo transporte
 * que {@code ChatController}: GET porque {@code EventSource} solo sabe hacer GET (los IDs viajan en
 * CSV), cada token como string JSON (el estandar SSE se come un espacio inicial), {@code
 * error-servidor} cuando algo revienta a mitad del stream (nunca un evento llamado {@code error}).
 *
 * <p><b>Los 400 se deciden ANTES de abrir el stream</b>, en este controlador y con los topes de
 * {@link Acciones#limites()}: no hay {@code @ExceptionHandler} en el repo y una fachada no lanza
 * para decir "lista vacia" — mismo idiom que {@code ChatController.feedback} con su {@code
 * badRequest()}. El cuerpo del 400 es un unico evento {@code error-cliente} con el motivo: {@code
 * EventSource} no puede leerlo (por eso la UI deshabilita el control con {@code
 * /api/acciones/limites} antes de llegar aqui), pero el cliente del traductor del chat, que usa
 * {@code fetch}, si.
 */
@RestController
class AccionesController {

  /**
   * ISO 639-1 y nada mas: lo que el cliente manda va derecho a un prompt, y el {@code Redactor}
   * solo sabe nombrar los codigos que el JDK conoce. Los de tres letras que devuelve la deteccion
   * los normaliza el propio Redactor; por aqui no pasan.
   */
  private static final Pattern CODIGO_IDIOMA = Pattern.compile("^[a-z]{2}$");

  private static final Set<String> IDIOMAS_ISO = Set.of(Locale.getISOLanguages());

  private static final String MENSAJE_IDIOMA_INVALIDO =
      "El idioma debe ser un código ISO 639-1 de dos letras (por ejemplo es, en, pt)";

  private final Acciones acciones;

  AccionesController(Acciones acciones) {
    this.acciones = acciones;
  }

  /** {@code origen} vacio o {@code "auto"} = detectar. */
  record TextoWeb(@NotBlank String texto, String origen, @NotBlank String destino) {}

  /** Publico y de solo lectura: dos numeros para que la UI deshabilite el control a tiempo. */
  @GetMapping("/api/acciones/limites")
  Limites limites() {
    return acciones.limites();
  }

  /**
   * {@code etiqueta}, {@code cobertura}, {@code citas}, luego {@code lectura}×n (una por pasada
   * sobre cada documento largo; ninguna si todos caben), y la salida: {@code token}×n para resumir
   * y sintetizar, un unico {@code resultado} (el JSON estructurado) para preguntas e ideas; {@code
   * fin}.
   *
   * @param tipo {@code resumir}, {@code sintetizar}, {@code preguntas} o {@code ideas}; otro es 404
   *     (con {@code KB_API_TOKEN} configurado, 401 antes: el filtro solo exceptua estas cuatro
   *     rutas exactas)
   * @param idioma codigo ISO 639-1 del resultado; ausente = español
   */
  @GetMapping(value = "/api/acciones/{tipo}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<Flux<ServerSentEvent<Object>>> accion(
      @PathVariable String tipo,
      @RequestParam(required = false) String documentos,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String idioma) {
    Tipo tipoAccion = tipoDe(tipo);
    if (tipoAccion == null) {
      return ResponseEntity.notFound().build();
    }
    List<Long> ids;
    String codigo;
    try {
      ids = idsValidados(documentos);
      codigo = idiomaValidado(idioma == null || idioma.isBlank() ? "es" : idioma);
    } catch (IllegalArgumentException invalida) {
      return rechazo(invalida.getMessage());
    }
    ProyectoId proyecto = ParametrosWeb.proyectoDe(projectId);

    ResultadoDeAccion resultado = acciones.ejecutar(tipoAccion, ids, proyecto, codigo);
    return stream(
        Flux.concat(
            cabecera(resultado.etiqueta(), resultado.cobertura(), resultado.citas()),
            resultado.eventos().map(AccionesController::eventoDe)));
  }

  private static ServerSentEvent<Object> eventoDe(EventoAccion evento) {
    return switch (evento) {
      case EventoAccion.Lectura e -> json("lectura", e);
      case EventoAccion.Token e -> json("token", e.fragmento());
      case EventoAccion.Resultado e -> json("resultado", e.valor());
    };
  }

  /**
   * {@code etiqueta}, {@code documentos} (uno por documento con sus bloques totales; 0 = no
   * indexado) y luego, por documento y en orden: {@code idioma-detectado} (solo si el origen era
   * detectar), {@code documento-omitido} o la secuencia {@code progreso}/{@code texto} por bloque;
   * {@code fin} al final.
   *
   * @param origen codigo ISO 639-1; ausente o {@code auto} = detectar por documento
   */
  @GetMapping(
      value = "/api/acciones/traducir-documentos",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<Flux<ServerSentEvent<Object>>> traducirDocumentos(
      @RequestParam(required = false) String documentos,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String origen,
      @RequestParam(required = false) String destino) {
    List<Long> ids;
    String origenValidado;
    String destinoValidado;
    try {
      ids = idsValidados(documentos);
      origenValidado = origenValidado(origen);
      destinoValidado = idiomaValidado(destino);
    } catch (IllegalArgumentException invalida) {
      return rechazo(invalida.getMessage());
    }
    TraduccionDeDocumentos resultado =
        acciones.traducirDocumentos(
            ids, ParametrosWeb.proyectoDe(projectId), origenValidado, destinoValidado);
    Flux<ServerSentEvent<Object>> cuerpo =
        Flux.concat(
            Flux.just(
                json("etiqueta", resultado.etiqueta()), json("documentos", resultado.documentos())),
            resultado.eventos().map(AccionesController::eventoDe));
    return stream(cuerpo);
  }

  /**
   * El traductor del chat (modo traducir y «Traducir» sobre un turno). POST con cuerpo JSON porque
   * una respuesta larga del asistente no cabe en una URL; el cliente lee el {@code
   * text/event-stream} con {@code fetch}. Eventos: {@code idioma-detectado} (el origen, dado o
   * detectado), {@code token}×n, {@code fin}.
   */
  @PostMapping(
      value = "/api/acciones/traducir-texto",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<Flux<ServerSentEvent<Object>>> traducirTexto(@Valid @RequestBody TextoWeb texto) {
    String origenValidado;
    String destinoValidado;
    try {
      int maximo = acciones.limites().maxCaracteresTexto();
      if (texto.texto().length() > maximo) {
        throw new IllegalArgumentException(
            "El texto a traducir supera el máximo de %d caracteres (tiene %d)"
                .formatted(maximo, texto.texto().length()));
      }
      origenValidado = origenValidado(texto.origen());
      destinoValidado = idiomaValidado(texto.destino());
    } catch (IllegalArgumentException invalida) {
      return rechazo(invalida.getMessage());
    }
    TextoTraducido resultado =
        acciones.traducirTexto(texto.texto(), origenValidado, destinoValidado);
    Flux<ServerSentEvent<Object>> cuerpo =
        Flux.concat(
            resultado.idiomaOrigen().map(codigo -> json("idioma-detectado", codigo)).flux(),
            resultado.texto().map(token -> json("token", token)));
    return stream(cuerpo);
  }

  private static Tipo tipoDe(String tipo) {
    return switch (tipo == null ? "" : tipo.toLowerCase(Locale.ROOT)) {
      case "resumir" -> Tipo.RESUMIR;
      case "sintetizar" -> Tipo.SINTETIZAR;
      case "preguntas" -> Tipo.PREGUNTAS;
      case "ideas" -> Tipo.IDEAS;
      default -> null;
    };
  }

  /** Sin duplicados, no vacia y dentro del tope: lo que la fachada da por hecho. */
  private List<Long> idsValidados(String documentosCsv) {
    List<Long> ids =
        new ArrayList<>(new LinkedHashSet<>(ParametrosWeb.documentosDe(documentosCsv)));
    if (ids.isEmpty()) {
      throw new IllegalArgumentException("Elige al menos un documento");
    }
    int maximo = acciones.limites().maxDocumentos();
    if (ids.size() > maximo) {
      throw new IllegalArgumentException(
          "Se pueden elegir hasta %d documentos a la vez (elegiste %d)"
              .formatted(maximo, ids.size()));
    }
    return ids;
  }

  /** {@code null} si hay que detectar; si no, un codigo valido. */
  private static String origenValidado(String origen) {
    if (origen == null || origen.isBlank() || "auto".equalsIgnoreCase(origen.strip())) {
      return null;
    }
    return idiomaValidado(origen);
  }

  /**
   * Un codigo de idioma que vaya a un prompt tiene que ser un codigo, no texto libre: {@code und} y
   * {@code auto} tampoco sirven como destino.
   */
  private static String idiomaValidado(String idioma) {
    String codigo = idioma == null ? "" : idioma.strip().toLowerCase(Locale.ROOT);
    if (!CODIGO_IDIOMA.matcher(codigo).matches() || !IDIOMAS_ISO.contains(codigo)) {
      throw new IllegalArgumentException(MENSAJE_IDIOMA_INVALIDO);
    }
    return codigo;
  }

  private static Flux<ServerSentEvent<Object>> cabecera(
      String etiqueta, List<CoberturaDocumento> cobertura, List<Cita> citas) {
    return Flux.just(
        json("etiqueta", etiqueta), json("cobertura", cobertura), json("citas", citas));
  }

  private static ServerSentEvent<Object> eventoDe(EventoTraduccion evento) {
    return switch (evento) {
      case EventoTraduccion.IdiomaDetectado e -> json("idioma-detectado", e);
      case EventoTraduccion.Omitido e -> json("documento-omitido", e);
      case EventoTraduccion.Progreso e -> json("progreso", e);
      case EventoTraduccion.Texto e -> json("texto", e);
    };
  }

  /** Todo dato viaja como JSON: ver el javadoc de {@code ChatController.chat} sobre el escape. */
  private static ServerSentEvent<Object> json(String nombre, Object dato) {
    return ServerSentEvent.builder().event(nombre).<Object>data(Json.escribir(dato)).build();
  }

  private static ResponseEntity<Flux<ServerSentEvent<Object>>> stream(
      Flux<ServerSentEvent<Object>> cuerpo) {
    Flux<ServerSentEvent<Object>> fin =
        Flux.just(ServerSentEvent.builder().event("fin").<Object>data("").build());
    // El .onErrorResume no es defensivo: la cabecera text/event-stream ya salio cuando
    // el Flux revienta, asi que la unica forma de que la pagina sepa QUE fallo es un
    // evento mas (ver ChatController.chat).
    Flux<ServerSentEvent<Object>> conCierre =
        Flux.concat(cuerpo, fin)
            .onErrorResume(
                error -> Flux.just(json("error-servidor", MensajesDeError.mensajeDe(error))));
    return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(conCierre);
  }

  private static ResponseEntity<Flux<ServerSentEvent<Object>>> rechazo(String motivo) {
    return ResponseEntity.badRequest()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(Flux.just(json("error-cliente", motivo)));
  }
}
