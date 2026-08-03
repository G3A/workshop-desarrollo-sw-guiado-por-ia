package co.g3a.baseconocimiento.teams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;
import co.g3a.baseconocimiento.orquestacion.Consultar;

/**
 * Responde una Activity de tipo {@code message}: indicador de escritura, las
 * siete etapas del pipeline vía {@link Consultar}, y la respuesta como
 * Adaptive Card.
 *
 * <p>Separado de {@link BotController} a propósito: {@link #procesar} corre
 * sincrónico y se puede probar directo, sin depender del hilo virtual que
 * {@link #procesarAsync} usa de verdad contra una petición HTTP real.
 */
@Component
class ProcesadorDeMensajes {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorDeMensajes.class);

    private final Consultar consultar;
    private final ClienteConectorBotFramework conector;

    ProcesadorDeMensajes(Consultar consultar, ClienteConectorBotFramework conector) {
        this.consultar = consultar;
        this.conector = conector;
    }

    /**
     * El webhook de {@code /api/messages} ya devolvió 200: esto corre en su
     * propio hilo virtual porque la síntesis tarda minutos en CPU (ver
     * Riesgos vivos del plan) y no puede bloquear esa respuesta.
     */
    void procesarAsync(Activity actividad) {
        Thread.ofVirtual().name("teams-mensaje").start(() -> procesar(actividad));
    }

    void procesar(Activity actividad) {
        conector.enviarEscribiendo(actividad);
        try {
            Respuesta respuesta =
                    consultar.responder(new Pregunta(actividad.text()), ProyectoId.POR_DEFECTO, Filtros.NINGUNO);
            conector.responderConTarjeta(actividad, respuesta);
        } catch (Exception e) {
            log.error("Fallo respondiendo la pregunta de Teams: {}", e.toString());
            conector.responderTexto(actividad, "Hubo un error respondiendo la pregunta. Intenta de nuevo.");
        }
    }
}
