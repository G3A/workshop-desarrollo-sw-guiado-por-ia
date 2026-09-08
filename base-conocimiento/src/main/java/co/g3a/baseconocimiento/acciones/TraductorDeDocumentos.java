package co.g3a.baseconocimiento.acciones;

import co.g3a.baseconocimiento.acciones.Acciones.DocumentoATraducir;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion.IdiomaDetectado;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion.Omitido;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion.Progreso;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion.Texto;
import co.g3a.baseconocimiento.acciones.Acciones.TraduccionDeDocumentos;
import co.g3a.baseconocimiento.acciones.PresupuestoDeContexto.DocumentoPlanificado;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Redactor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Traduce documentos completos, bloque a bloque. A diferencia de las acciones que redactan, aqui no
 * hay presupuesto de contexto ni pasadas de lectura: cada bloque es una llamada al LLM con su
 * propia ventana, asi que se recorre todo el documento y lo unico que se declara es el progreso.
 *
 * <p>Un bloque es una seccion entera si cabe en {@link #LARGO_BLOQUE}, o sus parrafos agrupados
 * hasta ese largo si no (hallazgo 12 de la revision adversarial: una seccion de 4000 caracteres en
 * un idioma caro en tokens truncaba la salida sin que nada lo dijera). Un {@code code_block} es un
 * bloque que pasa tal cual, sin LLM: el codigo no se traduce.
 *
 * <p>Todo es perezoso (hallazgo 3): ni la deteccion de idioma ni el cupo se tocan hasta que el
 * adaptador se suscribe, asi la cabecera SSE sale antes de la primera llamada al modelo. El cupo se
 * toma una vez por accion y se devuelve en el {@code doFinally} del flujo compuesto.
 */
@Component
class TraductorDeDocumentos {

  static final int LARGO_BLOQUE = 1500;

  private static final String TIPO_CODIGO = "code_block";

  /** Sin tope: la traduccion recorre el documento entero, el reparto solo ordena y agrupa. */
  private static final PresupuestoDeContexto SIN_PRESUPUESTO =
      new PresupuestoDeContexto(Integer.MAX_VALUE, Integer.MAX_VALUE);

  private final SeccionesRepositorio repo;
  private final Redactor redactor;
  private final CupoDeAcciones cupo;

  TraductorDeDocumentos(SeccionesRepositorio repo, Redactor redactor, CupoDeAcciones cupo) {
    this.repo = repo;
    this.redactor = redactor;
    this.cupo = cupo;
  }

  record Bloque(String texto, boolean esCodigo) {}

  /** {@code muestra}: con que texto se detecta el idioma (la primera seccion que no sea codigo). */
  record DocumentoEnBloques(
      long documentoId, String titulo, String uri, List<Bloque> bloques, String muestra) {}

  /**
   * @param origen codigo ISO 639-1, o {@code null} para detectarlo por documento
   * @param destino codigo ISO 639-1
   */
  TraduccionDeDocumentos traducir(
      List<Long> documentos, ProyectoId proyecto, String origen, String destino) {
    List<Long> unicos = new ArrayList<>(new LinkedHashSet<>(documentos));
    List<Seccion> secciones = repo.seccionesDe(unicos, proyecto.valor());
    List<DocumentoPlanificado> ordenados = SIN_PRESUPUESTO.planificar(unicos, secciones);
    String etiqueta = PresupuestoDeContexto.etiqueta("Traducción de", ordenados);

    List<DocumentoEnBloques> enBloques =
        ordenados.stream()
            .map(
                d ->
                    new DocumentoEnBloques(
                        d.documentoId(),
                        d.titulo(),
                        d.uri(),
                        partir(d.secciones()),
                        muestraDe(d.secciones())))
            .toList();
    List<DocumentoATraducir> descripcion =
        enBloques.stream()
            .map(
                d ->
                    new DocumentoATraducir(
                        d.documentoId(), d.titulo(), d.uri(), d.bloques().size()))
            .toList();
    List<DocumentoEnBloques> indexados =
        enBloques.stream().filter(d -> !d.bloques().isEmpty()).toList();

    Flux<EventoTraduccion> eventos =
        indexados.isEmpty()
            ? Flux.empty()
            : Flux.defer(
                () -> {
                  if (!cupo.intentarTomar()) {
                    return Flux.error(
                        new Acciones.ServidorOcupado(
                            AccionesSobreDocumentos.MENSAJE_SERVIDOR_OCUPADO));
                  }
                  return Flux.fromIterable(indexados)
                      .concatMap(d -> Flux.defer(() -> traducirDocumento(d, origen, destino)))
                      .doFinally(signal -> cupo.liberar());
                });

    return new TraduccionDeDocumentos(etiqueta, descripcion, eventos);
  }

  private Flux<EventoTraduccion> traducirDocumento(
      DocumentoEnBloques d, String origen, String destino) {
    Mono<String> idioma =
        origen != null
            ? Mono.just(origen)
            : Mono.fromCallable(() -> redactor.detectarIdioma(d.muestra()));
    return idioma.flatMapMany(
        codigo -> {
          Flux<EventoTraduccion> cabecera =
              origen == null
                  ? Flux.just(new IdiomaDetectado(d.documentoId(), codigo))
                  : Flux.empty();
          // "und" no es un idioma: nunca se omite por el, se traduce y el modelo infiere.
          if (!"und".equals(codigo) && codigo.equals(destino)) {
            return cabecera.concatWith(Flux.just(new Omitido(d.documentoId(), codigo)));
          }
          int total = d.bloques().size();
          Flux<EventoTraduccion> bloques =
              Flux.range(0, total)
                  .concatMap(
                      i -> traducirBloque(d, d.bloques().get(i), i + 1, total, codigo, destino));
          return cabecera.concatWith(bloques);
        });
  }

  private Flux<EventoTraduccion> traducirBloque(
      DocumentoEnBloques d, Bloque bloque, int numero, int total, String origen, String destino) {
    long id = d.documentoId();
    Flux<EventoTraduccion> progreso = Flux.just(new Progreso(id, numero, total));
    Flux<EventoTraduccion> texto =
        bloque.esCodigo()
            ? Flux.just(new Texto(id, bloque.texto()))
            : Flux.defer(() -> redactor.traducir(bloque.texto(), origen, destino))
                .map(fragmento -> new Texto(id, fragmento));
    // Separador entre bloques, para que el .md que arma la UI no pegue parrafos.
    Flux<EventoTraduccion> separador =
        numero < total ? Flux.just(new Texto(id, "\n\n")) : Flux.empty();
    return progreso.concatWith(texto).concatWith(separador);
  }

  /** Una seccion entera si cabe; si no, sus parrafos agrupados hasta {@link #LARGO_BLOQUE}. */
  static List<Bloque> partir(List<Seccion> secciones) {
    List<Bloque> bloques = new ArrayList<>();
    for (Seccion s : secciones) {
      String texto = s.texto() == null ? "" : s.texto();
      if (TIPO_CODIGO.equals(s.tipo())) {
        bloques.add(new Bloque(texto, true));
      } else if (texto.length() <= LARGO_BLOQUE) {
        bloques.add(new Bloque(texto, false));
      } else {
        bloques.addAll(partirPorParrafos(texto));
      }
    }
    return bloques;
  }

  private static List<Bloque> partirPorParrafos(String texto) {
    List<Bloque> bloques = new ArrayList<>();
    StringBuilder actual = new StringBuilder();
    for (String parrafo : texto.split("\\n\\s*\\n")) {
      if (parrafo.length() > LARGO_BLOQUE) {
        // Un solo parrafo mas largo que un bloque: se corta a lo bruto, no hay mejor
        // frontera que respetar.
        if (!actual.isEmpty()) {
          bloques.add(new Bloque(actual.toString(), false));
          actual.setLength(0);
        }
        for (int i = 0; i < parrafo.length(); i += LARGO_BLOQUE) {
          bloques.add(
              new Bloque(
                  parrafo.substring(i, Math.min(parrafo.length(), i + LARGO_BLOQUE)), false));
        }
        continue;
      }
      if (!actual.isEmpty() && actual.length() + 2 + parrafo.length() > LARGO_BLOQUE) {
        bloques.add(new Bloque(actual.toString(), false));
        actual.setLength(0);
      }
      if (!actual.isEmpty()) {
        actual.append("\n\n");
      }
      actual.append(parrafo);
    }
    if (!actual.isEmpty()) {
      bloques.add(new Bloque(actual.toString(), false));
    }
    return bloques;
  }

  private static String muestraDe(List<Seccion> secciones) {
    return secciones.stream()
        .filter(s -> !TIPO_CODIGO.equals(s.tipo()))
        .map(Seccion::texto)
        .findFirst()
        .orElseGet(() -> secciones.isEmpty() ? "" : secciones.get(0).texto());
  }
}
