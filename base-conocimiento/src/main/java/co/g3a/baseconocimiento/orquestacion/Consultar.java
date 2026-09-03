package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.IdiomaRespuesta;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;
import java.util.List;
import java.util.Optional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * La fachada del nucleo: la unica puerta que los adaptadores pueden cruzar.
 *
 * <p>Que exista una sola operacion es deliberado. La UI web y el bot de Teams son piel: traducen un
 * mensaje entrante a una {@link Pregunta} y una {@link Respuesta} a su formato de salida. Ninguno
 * de los dos sabe que existen cuatro senales, un RRF ni un cross-encoder — y una prueba de ArchUnit
 * lo verifica en cada build.
 */
public interface Consultar {

  /**
   * Responde una pregunta contra el corpus del proyecto, con citas verificables.
   *
   * @param pregunta lo que pregunto la persona, en lenguaje natural
   * @param proyecto acota el corpus antes de que el planner elija herramientas
   * @param filtros restricciones opcionales; usa {@link Filtros#NINGUNO} si no hay
   */
  Respuesta responder(Pregunta pregunta, ProyectoId proyecto, Filtros filtros);

  /**
   * Qué hacer con el {@code Reformulador} cuando la búsqueda con la pregunta tal cual no alcanza.
   *
   * <p>Un tipo sellado y no un enum más un campo nulable: así {@link Elegida} no puede existir sin
   * su consulta, y {@link Automatica}/{@link Proponer} no pueden arrastrar una que nadie lee.
   */
  sealed interface ModoReformulacion {

    /**
     * Lo de siempre: reformula sola, repite la búsqueda con la primera alternativa y se queda con
     * la ronda que mejor pasa la puerta de relevancia. Es lo que usan Teams y {@code /api/ask}, que
     * no tienen cómo preguntarle nada a la persona a mitad de camino.
     */
    record Automatica() implements ModoReformulacion {}

    /**
     * Si la búsqueda original no alcanza y el {@code Reformulador} tiene alternativas, NO responde:
     * las devuelve en {@link RespuestaEnStreaming#reformulacionesPropuestas()} para que la persona
     * elija con cuál buscar (y en qué idioma leer la respuesta). El adaptador vuelve a llamar con
     * {@link Elegida}.
     */
    record Proponer() implements ModoReformulacion {}

    /**
     * Busca con esta consulta, sin pasar por el {@code Reformulador}. La pregunta que se responde
     * sigue siendo la original: esto solo cambia el texto con el que se consultan las herramientas.
     *
     * @param consultaDeBusqueda lo que eligió la persona; puede ser la propia pregunta original si
     *     prefirió no reformular
     */
    record Elegida(String consultaDeBusqueda) implements ModoReformulacion {
      public Elegida {
        if (consultaDeBusqueda == null || consultaDeBusqueda.isBlank()) {
          throw new IllegalArgumentException("La consulta elegida no puede ser vacia");
        }
      }
    }
  }

  /**
   * Lo que la persona puede decidir sobre cómo se responde, más allá de la pregunta en sí. {@link
   * #POR_DEFECTO} reproduce el comportamiento previo a que existieran estas opciones.
   */
  record Preferencias(ModoReformulacion reformulacion, IdiomaRespuesta idioma) {
    public static final Preferencias POR_DEFECTO =
        new Preferencias(new ModoReformulacion.Automatica(), IdiomaRespuesta.ESPANOL);
  }

  /**
   * Citas disponibles de inmediato — la etapa 5 (fusion + expansion) ya terminó — y el texto de la
   * síntesis en streaming, token a token. Existe para el adaptador web de F4: dejar que la UI
   * muestre las fuentes antes de que el LLM termine de redactar.
   *
   * @param consultaReformulada ver {@code Dominio.Respuesta.consultaReformulada} — disponible en el
   *     mismo momento que las citas, porque la reformulación ocurre antes de ejecutar las
   *     herramientas
   * @param queryLogId se resuelve recién cuando {@code texto} completa (ahí es cuando la etapa 7
   *     escribe la fila y se conoce el id) — nunca emite si el stream termina en error o se cancela
   *     antes, porque en ese caso nunca se llega a escribir en {@code query_log}
   * @param reformulacionesPropuestas solo con {@link ModoReformulacion.Proponer}: si no está vacía,
   *     NO hay respuesta — {@code citas} viene vacía, {@code texto} completa sin emitir nada y
   *     {@code queryLogId} nunca emite. El adaptador se las muestra a la persona y vuelve a llamar
   *     con {@link ModoReformulacion.Elegida}. Vacía en cualquier otro caso.
   */
  record RespuestaEnStreaming(
      List<Cita> citas,
      Flux<String> texto,
      String consultaReformulada,
      Mono<Long> queryLogId,
      List<String> reformulacionesPropuestas) {}

  /**
   * @param conversacionId identifica la conversación para poder reconectarse con {@link
   *     #estadoDeStream} tras un F5 a mitad de una respuesta; {@code null} = no persistir nada para
   *     reconectar (p. ej. un adaptador sin noción de conversación).
   * @param preferencias ver {@link Preferencias}; usa {@link Preferencias#POR_DEFECTO} si el
   *     adaptador no ofrece elegir
   */
  RespuestaEnStreaming responderEnStreaming(
      Pregunta pregunta,
      ProyectoId proyecto,
      Filtros filtros,
      Long conversacionId,
      Preferencias preferencias);

  /**
   * Estado de la pregunta más reciente de una conversación — para que la UI se reconecte después de
   * un F5 a mitad de una respuesta en vez de perderla por completo. {@code estado}: {@code
   * "en_curso"}, {@code "completo"} o {@code "error"}.
   *
   * @param queryLogId {@code null} mientras {@code estado} es {@code "en_curso"}, o si terminó en
   *     {@code "error"} (nunca se llegó a escribir en {@code query_log}) — presente en {@code
   *     "completo"}, para que la UI reconectada pueda ofrecer los mismos botones de feedback que
   *     tendría si nunca hubiera perdido la conexión
   */
  record EstadoStream(
      String estado,
      String pregunta,
      String projectId,
      String texto,
      List<Cita> citas,
      String reformulacion,
      Long queryLogId) {}

  Optional<EstadoStream> estadoDeStream(long conversacionId);

  /**
   * Registra que una respuesta ya mostrada sirvió o no, con un comentario opcional. {@code false}
   * si {@code queryLogId} no corresponde a ninguna fila real de {@code query_log} — el adaptador lo
   * traduce a un error de cliente, sin necesitar excepciones.
   *
   * <p>Se permiten varias filas por {@code queryLogId} (no hay login de persona en el MVP, así que
   * no hay identidad real contra la cual deduplicar); el adaptador es quien decide, del lado del
   * cliente, no admitir más de un click.
   *
   * <p>Riesgo aceptado y documentado, no resuelto acá: nada valida que quien manda el feedback
   * realmente haya visto la respuesta de ese {@code queryLogId} — ver el issue #3 y el plan de
   * implementación. Resolverlo de verdad requiere una noción de sesión/identidad que este MVP no
   * tiene; construirla es una iniciativa aparte.
   *
   * @param comentario límite de 2000 caracteres — asunción de producto, no pedida por el issue
   */
  boolean registrarFeedback(long queryLogId, boolean util, String comentario);

  /**
   * Vista previa casi instantánea: solo la señal de texto completo, sin embeddings ni cross-encoder
   * — el "keyword search on landing" del artículo, para mostrar algo mientras el pipeline completo
   * corre detrás.
   *
   * @param limite cuántos resultados como máximo; lo decide el adaptador
   * @param documentosPermitidos ver {@link Filtros#documentosPermitidos()}; vacío = sin restricción
   */
  List<Cita> previsualizar(
      Pregunta pregunta, ProyectoId proyecto, int limite, List<Long> documentosPermitidos);
}
