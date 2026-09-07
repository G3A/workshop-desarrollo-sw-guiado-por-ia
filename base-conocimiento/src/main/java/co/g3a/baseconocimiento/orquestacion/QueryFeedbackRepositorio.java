package co.g3a.baseconocimiento.orquestacion;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * La otra mitad de la auditoría de {@code query_log}: no solo qué respondió el sistema, sino si
 * sirvió. Ver el comentario de {@code V5__query_feedback.sql} sobre por qué se permiten varias
 * filas por {@code queryLogId} en vez de forzar una sola.
 */
@Repository
class QueryFeedbackRepositorio {

  record FeedbackRegistrado(
      long id, long queryLogId, boolean util, String comentario, Instant creadoEn) {}

  private final JdbcClient jdbc;

  QueryFeedbackRepositorio(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  boolean existe(long queryLogId) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT EXISTS(SELECT 1 FROM query_log WHERE id = :queryLogId)")
            .param("queryLogId", queryLogId)
            .query(Boolean.class)
            .single());
  }

  long registrar(long queryLogId, boolean util, String comentario) {
    return jdbc.sql(
            """
                        INSERT INTO query_feedback (query_log_id, util, comentario)
                        VALUES (:queryLogId, :util, :comentario)
                        RETURNING id
                        """)
        .param("queryLogId", queryLogId)
        .param("util", util)
        .param("comentario", comentario)
        .query(Long.class)
        .single();
  }

  /**
   * Las {@code limite} filas más recientes, para {@code GET /api/admin/feedback}. Sin paginación en
   * este corte — el tope es deliberado, no un olvido: ver el plan del issue #3.
   */
  List<FeedbackRegistrado> listarRecientes(int limite) {
    return jdbc.sql(
            """
                        SELECT id, query_log_id, util, comentario, creado_en
                        FROM query_feedback
                        ORDER BY creado_en DESC
                        LIMIT :limite
                        """)
        .param("limite", limite)
        .query(
            (rs, n) ->
                new FeedbackRegistrado(
                    rs.getLong("id"),
                    rs.getLong("query_log_id"),
                    rs.getBoolean("util"),
                    rs.getString("comentario"),
                    rs.getTimestamp("creado_en").toInstant()))
        .list();
  }
}
