package co.g3a.baseconocimiento.recuperacion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * El disparador periódico de {@link RecuperacionRepositorio#recalcularEstadisticasTerminos()}:
 * alimenta la señal 3 (supresión de relleno por IDF) sin tocarla en cada
 * búsqueda.
 *
 * <p>Deshabilitable con {@code kb.recuperacion.terminos.habilitado=false},
 * igual que el worker de ingesta: pruebas que no necesitan este barrido de
 * fondo lo apagan.
 */
@Component
@ConditionalOnProperty(prefix = "kb.recuperacion.terminos", name = "habilitado", matchIfMissing = true)
class RecalculoTerminosProgramador {

    private static final Logger log = LoggerFactory.getLogger(RecalculoTerminosProgramador.class);

    private final RecuperacionRepositorio repositorio;

    RecalculoTerminosProgramador(RecuperacionRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    @Scheduled(fixedDelayString = "${kb.recuperacion.terminos.intervalo-ms:60000}")
    void recalcular() {
        try {
            repositorio.recalcularEstadisticasTerminos();
        } catch (Exception e) {
            log.warn("Fallo al recalcular term_stats: {}", e.toString());
        }
    }
}
