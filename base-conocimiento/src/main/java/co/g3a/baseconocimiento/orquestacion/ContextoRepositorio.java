package co.g3a.baseconocimiento.orquestacion;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Etapa 5: expansión de contexto. Dado un fragmento elegido, trae sus
 * secciones vecinas (mismo documento, {@code ord} inmediato anterior y
 * siguiente) para que la síntesis tenga más alrededor que el chunk exacto que
 * matcheó — el equivalente de "secciones vecinas" del plan.
 *
 * <p>"Hermanos del hilo" (la otra mitad de la expansión que describe el plan)
 * llega con los hilos de Teams en F6: hasta entonces, todo lo que existe es
 * documentación troceada por encabezados, donde la adyacencia por {@code ord}
 * ya es la relación correcta.
 */
@Repository
class ContextoRepositorio {

    private final JdbcClient jdbc;

    ContextoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Vecino(int ord, String texto) {
    }

    /** A lo sumo dos filas: {@code ord - 1} y {@code ord + 1}, en ese orden. Vacío si el documento no existe. */
    List<Vecino> vecinos(long documentoId, int ord) {
        return jdbc.sql("""
                        SELECT ord, text FROM chunks
                        WHERE document_id = :documentoId AND ord IN (:anterior, :siguiente)
                        ORDER BY ord
                        """)
                .param("documentoId", documentoId)
                .param("anterior", ord - 1)
                .param("siguiente", ord + 1)
                .query((rs, n) -> new Vecino(rs.getInt("ord"), rs.getString("text")))
                .list();
    }
}
