package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El SQL propio de {@code recent_commits} y {@code subsystem_index}: no son una señal más de {@code
 * recuperacion} (no rankean por relevancia a una consulta), son listados/agregados directos sobre
 * el corpus.
 */
@Repository
class HerramientasRepositorio {

  private final JdbcClient jdbc;

  HerramientasRepositorio(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@code recent_commits}: el primer chunk de cada documento de una fuente, por frescura. Hoy solo
   * hay fuentes {@code local_docs}; devuelve vacío para {@code local_git} hasta que el conector de
   * F6 exista — no es un error, es el estado esperado de un conector todavía deshabilitado.
   */
  List<Fragmento> masRecientesPorFuente(String sourceKind, String projectId, int limite) {
    return jdbc.sql(
            """
                        SELECT c.id, c.document_id, d.uri, d.title, c.text, c.kind, c.ord, c.source_updated_at
                        FROM chunks c
                        JOIN documents d ON d.id = c.document_id
                        JOIN sources s ON s.id = c.source_id
                        WHERE s.kind = :sourceKind AND c.project_id = :projectId AND c.ord = 0
                        ORDER BY c.source_updated_at DESC
                        LIMIT :limite
                        """)
        .param("sourceKind", sourceKind)
        .param("projectId", projectId)
        .param("limite", limite)
        .query(HerramientasRepositorio::mapearFragmento)
        .list();
  }

  /**
   * {@code subsystem_index}: agrega {@code distilled.systems_mentioned} across el corpus. Ese campo
   * solo lo puebla la destilación por LLM de hilos de Teams (F6); para documentos y código de hoy,
   * que son heurísticos (ver {@code package-info} de {@code ingesta}), queda vacío — el índice se
   * puebla a medida que existan fuentes destiladas, no antes.
   */
  List<Fragmento> indiceDeSubsistemas(String projectId, int limite) {
    return jdbc.sql(
            """
                        SELECT sistema, count(*) AS menciones,
                               (array_agg(id ORDER BY actualizado DESC))[1] AS ejemplo_id,
                               (array_agg(document_id ORDER BY actualizado DESC))[1] AS ejemplo_documento_id,
                               (array_agg(uri ORDER BY actualizado DESC))[1] AS ejemplo_uri,
                               (array_agg(titulo ORDER BY actualizado DESC))[1] AS ejemplo_titulo,
                               (array_agg(ord ORDER BY actualizado DESC))[1] AS ejemplo_ord,
                               max(actualizado) AS ejemplo_actualizado
                        FROM (
                            SELECT c.id, c.document_id, c.ord, c.source_updated_at AS actualizado,
                                   d.uri, d.title AS titulo,
                                   jsonb_array_elements_text(c.distilled -> 'systems_mentioned') AS sistema
                            FROM chunks c
                            JOIN documents d ON d.id = c.document_id
                            WHERE c.project_id = :projectId
                              AND jsonb_typeof(c.distilled -> 'systems_mentioned') = 'array'
                        ) t
                        GROUP BY sistema
                        ORDER BY menciones DESC
                        LIMIT :limite
                        """)
        .param("projectId", projectId)
        .param("limite", limite)
        .query(HerramientasRepositorio::mapearSubsistema)
        .list();
  }

  /**
   * Cuantos chunks tiene el proyecto ahora mismo: la base del umbral dinamico de {@link
   * UmbralRelevancia} (ADR-0008) — no es una señal de relevancia, es el tamaño del corpus contra el
   * que se está preguntando.
   */
  long contarChunks(String projectId) {
    return jdbc.sql("SELECT count(*) FROM chunks WHERE project_id = :projectId")
        .param("projectId", projectId)
        .query(Long.class)
        .single();
  }

  private static Fragmento mapearFragmento(ResultSet rs, int n) throws SQLException {
    return new Fragmento(
        rs.getLong("id"),
        rs.getLong("document_id"),
        rs.getString("uri"),
        rs.getString("title"),
        rs.getString("text"),
        rs.getString("kind"),
        rs.getInt("ord"),
        rs.getTimestamp("source_updated_at").toInstant(),
        Map.of(),
        0.0,
        null);
  }

  private static Fragmento mapearSubsistema(ResultSet rs, int n) throws SQLException {
    String sistema = rs.getString("sistema");
    long menciones = rs.getLong("menciones");
    String texto =
        "Sistema \"%s\": mencionado en %d fragmentos del corpus. Ejemplo: %s"
            .formatted(sistema, menciones, rs.getString("ejemplo_titulo"));
    Instant actualizado = rs.getTimestamp("ejemplo_actualizado").toInstant();
    return new Fragmento(
        rs.getLong("ejemplo_id"),
        rs.getLong("ejemplo_documento_id"),
        rs.getString("ejemplo_uri"),
        "Subsistema: " + sistema,
        texto,
        "subsystem_summary",
        rs.getInt("ejemplo_ord"),
        actualizado,
        Map.of(),
        0.0,
        null);
  }
}
