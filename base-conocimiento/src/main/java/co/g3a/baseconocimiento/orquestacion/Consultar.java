package co.g3a.baseconocimiento.orquestacion;

import java.util.List;
import java.util.Optional;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;

import reactor.core.publisher.Flux;

/**
 * La fachada del nucleo: la unica puerta que los adaptadores pueden cruzar.
 *
 * <p>Que exista una sola operacion es deliberado. La UI web y el bot de Teams
 * son piel: traducen un mensaje entrante a una {@link Pregunta} y una
 * {@link Respuesta} a su formato de salida. Ninguno de los dos sabe que existen
 * cuatro senales, un RRF ni un cross-encoder — y una prueba de ArchUnit lo
 * verifica en cada build.
 */
public interface Consultar {

    /**
     * Responde una pregunta contra el corpus del proyecto, con citas verificables.
     *
     * @param pregunta lo que pregunto la persona, en lenguaje natural
     * @param proyecto acota el corpus antes de que el planner elija herramientas
     * @param filtros  restricciones opcionales; usa {@link Filtros#NINGUNO} si no hay
     */
    Respuesta responder(Pregunta pregunta, ProyectoId proyecto, Filtros filtros);

    /**
     * Citas disponibles de inmediato — la etapa 5 (fusion + expansion) ya
     * terminó — y el texto de la síntesis en streaming, token a token. Existe
     * para el adaptador web de F4: dejar que la UI muestre las fuentes antes de
     * que el LLM termine de redactar.
     *
     * @param consultaReformulada ver {@code Dominio.Respuesta.consultaReformulada} —
     *                            disponible en el mismo momento que las citas, porque la
     *                            reformulación ocurre antes de ejecutar las herramientas
     */
    record RespuestaEnStreaming(List<Cita> citas, Flux<String> texto, String consultaReformulada) {
    }

    /**
     * @param conversacionId identifica la conversación para poder reconectarse con
     *                       {@link #estadoDeStream} tras un F5 a mitad de una respuesta;
     *                       {@code null} = no persistir nada para reconectar (p. ej. un
     *                       adaptador sin noción de conversación).
     */
    RespuestaEnStreaming responderEnStreaming(Pregunta pregunta, ProyectoId proyecto, Filtros filtros, Long conversacionId);

    /**
     * Estado de la pregunta más reciente de una conversación — para que la UI
     * se reconecte después de un F5 a mitad de una respuesta en vez de perderla
     * por completo. {@code estado}: {@code "en_curso"}, {@code "completo"} o
     * {@code "error"}.
     */
    record EstadoStream(
            String estado, String pregunta, String projectId, String texto, List<Cita> citas,
            String reformulacion) {
    }

    Optional<EstadoStream> estadoDeStream(long conversacionId);

    /**
     * Vista previa casi instantánea: solo la señal de texto completo, sin
     * embeddings ni cross-encoder — el "keyword search on landing" del
     * artículo, para mostrar algo mientras el pipeline completo corre detrás.
     *
     * @param limite               cuántos resultados como máximo; lo decide el adaptador
     * @param documentosPermitidos ver {@link Filtros#documentosPermitidos()}; vacío = sin restricción
     */
    List<Cita> previsualizar(Pregunta pregunta, ProyectoId proyecto, int limite, List<Long> documentosPermitidos);
}
