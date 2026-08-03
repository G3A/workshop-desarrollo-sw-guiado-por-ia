package co.g3a.baseconocimiento.recuperacion;

import java.time.Instant;
import java.util.Map;

/**
 * Un chunk tras pasar por {@link RrfFusion}: conserva el puntaje crudo de
 * cada señal en la que apareció (para la traza) más el puntaje fusionado.
 */
record CandidatoFusionado(
        long chunkId,
        long documentoId,
        String uri,
        String titulo,
        String texto,
        String tipo,
        int ord,
        Instant actualizadoEn,
        Map<Senal, Double> puntajesPorSenal,
        double rrf) {
}
