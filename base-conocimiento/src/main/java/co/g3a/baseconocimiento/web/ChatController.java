package co.g3a.baseconocimiento.web;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.orquestacion.Consultar;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * REST y SSE para la página de {@code index.html}: vista previa instantánea por palabra clave y la
 * pregunta completa en streaming, con citas.
 *
 * <p>Solo depende de {@link Consultar} y de {@code compartido} — nunca de {@code recuperacion},
 * {@code ingesta}, {@code modelos} ni {@code llm}. Ese límite es el que hace que "adaptador"
 * signifique algo, y {@code ArquitecturaTest} lo verifica en cada build.
 */
@RestController
class ChatController {

  private final Consultar consultar;
  private final int previewLimite;

  ChatController(Consultar consultar, @Value("${kb.web.preview-limite:8}") int previewLimite) {
    this.consultar = consultar;
    this.previewLimite = previewLimite;
  }

  /**
   * @param documentos IDs de {@code documents} a los que acotar la búsqueda; null/vacío = sin
   *     restricción.
   */
  record PreguntaWeb(@NotBlank String q, String projectId, List<Long> documentos) {}

  @PostMapping("/api/preview")
  List<Cita> previsualizar(@Valid @RequestBody PreguntaWeb pregunta) {
    return consultar.previsualizar(
        new Pregunta(pregunta.q()),
        proyectoDe(pregunta.projectId()),
        previewLimite,
        documentosDe(pregunta.documentos()));
  }

  /**
   * GET, no POST: {@code EventSource} del navegador solo sabe hacer GET, por eso la pregunta viaja
   * en query params y no en un cuerpo JSON.
   *
   * <p>Hasta cuatro tipos de evento, en orden: {@code citas} (una vez, con las fuentes que la etapa
   * 5 ya resolvió), {@code reformulacion} (solo si el {@code Reformulador} cambió el texto de
   * búsqueda — omitido en el caso normal, no uno vacío), {@code token} (varias veces, el texto de
   * la síntesis a medida que Ollama lo genera) y {@code fin} (una vez, para que el cliente cierre
   * la conexión en vez de esperar más).
   *
   * <p><b>Cada token va como string JSON, no como texto crudo.</b> El estándar SSE le quita al
   * valor de un campo {@code data:} un único espacio inicial (es la convención para el delimitador
   * "{@code data: }"). Muchos tokens de un LLM empiezan justo con un espacio real (" el", "
   * servicio", antes de cada palabra nueva) — sin este escape, ese espacio se confunde con el
   * delimitador y el navegador lo descarta. Verificado en vivo con Playwright: sin el escape, la
   * respuesta llegaba como <i>"Paradesplegarelservicio..."</i>, sin ningún espacio entre palabras.
   */
  @GetMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  Flux<ServerSentEvent<Object>> chat(
      @RequestParam @NotBlank String q,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String documentos,
      @RequestParam(required = false) Long conversacionId) {
    Filtros filtros = Filtros.conDocumentos(documentosDe(documentos));
    Consultar.RespuestaEnStreaming resultado =
        consultar.responderEnStreaming(
            new Pregunta(q), proyectoDe(projectId), filtros, conversacionId);

    Flux<ServerSentEvent<Object>> eventoCitas =
        Flux.just(
            ServerSentEvent.builder().event("citas").data((Object) resultado.citas()).build());

    // Mismo escape que los tokens (ver el javadoc de arriba): la consulta reformulada
    // es texto libre del LLM, puede empezar con espacio o traer comillas.
    Flux<ServerSentEvent<Object>> eventoReformulacion =
        resultado.consultaReformulada() == null
            ? Flux.empty()
            : Flux.just(
                ServerSentEvent.builder()
                    .event("reformulacion")
                    .<Object>data(Json.escribir(resultado.consultaReformulada()))
                    .build());

    Flux<ServerSentEvent<Object>> eventosTexto =
        resultado
            .texto()
            .map(
                token ->
                    ServerSentEvent.builder()
                        .event("token")
                        .<Object>data(Json.escribir(token))
                        .build());

    // Se suscribe recien despues de que eventosTexto agota su Flux (Flux.concat
    // es secuencial) -- para ese momento el doOnComplete de Orquestador ya
    // corrio y el Mono tiene el id real. Si el stream termino en error o se
    // cancelo antes, el Mono nunca emite y este tramo del concat simplemente
    // no manda nada (no rompe la secuencia).
    Flux<ServerSentEvent<Object>> eventoQueryLogId =
        resultado
            .queryLogId()
            .map(
                id ->
                    ServerSentEvent.builder()
                        .event("queryLogId")
                        .<Object>data(String.valueOf(id))
                        .build())
            .flux();

    Flux<ServerSentEvent<Object>> eventoFin =
        Flux.just(ServerSentEvent.builder().event("fin").<Object>data("").build());

    return Flux.concat(eventoCitas, eventoReformulacion, eventosTexto, eventoQueryLogId, eventoFin);
  }

  /**
   * Para que la página se reconecte tras un F5 a mitad de una respuesta — ver el javadoc de {@code
   * Orquestador.MENSAJE_SERVIDOR_OCUPADO} y {@code StreamsEnCursoRepositorio}. 404 si esta
   * conversación nunca preguntó nada (o ya no queda registro).
   */
  @GetMapping("/api/chat/estado")
  ResponseEntity<Consultar.EstadoStream> estado(@RequestParam long conversacionId) {
    return consultar
        .estadoDeStream(conversacionId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * @param comentario límite de 2000 caracteres — asunción de producto documentada en {@code
   *     Consultar.registrarFeedback}, no pedida por el issue #3
   */
  record FeedbackWeb(
      @NotNull Long queryLogId, @NotNull Boolean util, @Size(max = 2000) String comentario) {}

  /**
   * Público, sin token — ver {@code ApiTokenFilter}: la página de chat no maneja el token, así que
   * si esta ruta lo exigiera el botón nunca podría llamarla.
   */
  @PostMapping("/api/feedback")
  ResponseEntity<Void> feedback(@Valid @RequestBody FeedbackWeb feedback) {
    boolean registrado =
        consultar.registrarFeedback(feedback.queryLogId(), feedback.util(), feedback.comentario());
    return registrado ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
  }

  private static ProyectoId proyectoDe(String projectId) {
    return (projectId == null || projectId.isBlank())
        ? ProyectoId.POR_DEFECTO
        : new ProyectoId(projectId);
  }

  private static List<Long> documentosDe(List<Long> documentos) {
    return documentos == null ? List.of() : documentos;
  }

  /**
   * IDs separados por coma: {@code EventSource} solo sabe hacer GET, así que el filtro viaja en un
   * query param plano, no en un cuerpo JSON con lista (mismo motivo que {@code q}/{@code projectId}
   * — ver el javadoc de {@link #chat}).
   */
  private static List<Long> documentosDe(String documentosCsv) {
    if (documentosCsv == null || documentosCsv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(documentosCsv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(Long::parseLong)
        .toList();
  }
}
