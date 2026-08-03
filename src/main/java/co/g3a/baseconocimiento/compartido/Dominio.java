package co.g3a.baseconocimiento.compartido;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Vocabulario compartido entre el nucleo y los adaptadores.
 *
 * <p>Se agrupa en un solo archivo porque son records sin comportamiento: separarlos
 * en ocho archivos daria ocho lugares donde mirar para entender una sola idea.
 */
public final class Dominio {

    private Dominio() {
    }

    /** Identificador del proyecto: filtra el corpus ANTES del planner. */
    public record ProyectoId(String valor) {
        public static final ProyectoId POR_DEFECTO = new ProyectoId("default");

        public ProyectoId {
            if (valor == null || valor.isBlank()) {
                throw new IllegalArgumentException("El proyecto no puede ser vacio");
            }
        }
    }

    /** Pregunta del usuario, tal como llego por cualquiera de los dos adaptadores. */
    public record Pregunta(String texto) {
        public Pregunta {
            if (texto == null || texto.isBlank()) {
                throw new IllegalArgumentException("La pregunta no puede ser vacia");
            }
        }
    }

    /** Restricciones opcionales sobre el corpus. */
    public record Filtros(List<String> fuentes, Instant desde, int maxCandidatos) {
        public static final Filtros NINGUNO = new Filtros(List.of(), null, 20);
    }

    /**
     * Un fragmento recuperado, con la traza de por que llego hasta aqui.
     *
     * @param ord              posicion del chunk dentro de su documento; la usa la expansion
     *                         de contexto de F3 para pedir las secciones vecinas
     * @param puntajesPorSenal puntaje crudo de cada una de las cuatro senales
     * @param rrf              puntaje de la fusion por rango
     * @param rerank           puntaje 0-10 del cross-encoder, o null si no llego a esa etapa
     */
    public record Fragmento(
            long id,
            long documentoId,
            String uri,
            String titulo,
            String texto,
            String tipo,
            int ord,
            Instant actualizadoEn,
            Map<String, Double> puntajesPorSenal,
            double rrf,
            Double rerank) {
    }

    /** Referencia verificable que acompaña a toda respuesta. */
    public record Cita(String uri, String titulo, String extracto, String fuente) {
    }

    /**
     * Respuesta sintetizada.
     *
     * @param advertencias evidencia contradictoria o desactualizada que el sintetizador
     *                     debe declarar en vez de dar falsa certeza
     */
    public record Respuesta(
            String texto,
            List<Cita> citas,
            List<String> advertencias,
            long latenciaMs) {
    }
}
