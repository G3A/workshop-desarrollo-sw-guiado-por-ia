package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Estado de la pregunta más reciente de cada conversación, para que la UI se pueda reconectar
 * después de un F5 a mitad de una respuesta — ver el javadoc de {@code
 * Orquestador.MENSAJE_SERVIDOR_OCUPADO} sobre por qué el servidor no se entera de esa desconexión.
 *
 * <p>Vive aparte de {@link QueryLogRepositorio} a propósito: {@code query_log} es la bitácora
 * permanente de auditoría (una fila por pregunta, para siempre); esto es efímero, una sola fila por
 * conversación que se pisa con la pregunta más reciente.
 */
@Repository
class StreamsEnCursoRepositorio {

  record Estado(
      String estado,
      String pregunta,
      String projectId,
      String texto,
      List<Cita> citas,
      String reformulacion,
      Long queryLogId) {}

  private final JdbcClient jdbc;

  StreamsEnCursoRepositorio(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Se llama al arrancar, antes de la etapa 1 -- asi un F5 al toque de preguntar ya ve "en_curso".
   */
  void iniciar(long conversacionId, String pregunta, String projectId) {
    jdbc.sql(
            """
                        INSERT INTO streams_en_curso (conversacion_id, pregunta, project_id, estado, texto, citas)
                        VALUES (:conversacionId, :pregunta, :projectId, 'en_curso', '', '[]'::jsonb)
                        ON CONFLICT (conversacion_id) DO UPDATE SET
                            pregunta = EXCLUDED.pregunta, project_id = EXCLUDED.project_id,
                            estado = 'en_curso', texto = '', citas = '[]'::jsonb,
                            reformulacion = NULL, actualizado_en = now()
                        """)
        .param("conversacionId", conversacionId)
        .param("pregunta", pregunta)
        .param("projectId", projectId)
        .update();
  }

  /**
   * Se llama apenas se conocen las citas (etapa 5) -- si alguien reconecta mientras sintetiza, ya
   * las ve.
   */
  void actualizarCitas(long conversacionId, List<Cita> citas, String reformulacion) {
    jdbc.sql(
            """
                        UPDATE streams_en_curso
                        SET citas = CAST(:citas AS jsonb), reformulacion = :reformulacion, actualizado_en = now()
                        WHERE conversacion_id = :conversacionId
                        """)
        .param("conversacionId", conversacionId)
        .param("citas", Json.escribir(citas))
        .param("reformulacion", reformulacion)
        .update();
  }

  /**
   * {@code estado}: "completo" o "error" -- nunca "en_curso" desde aca, eso solo lo pone {@link
   * #iniciar}. {@code queryLogId} es {@code null} en el camino de error (nunca se llego a escribir
   * en {@code query_log}) -- ver el javadoc de {@code Consultar.EstadoStream}.
   */
  void finalizar(long conversacionId, String estado, String texto, Long queryLogId) {
    jdbc.sql(
            """
                        UPDATE streams_en_curso
                        SET estado = :estado, texto = :texto, query_log_id = :queryLogId, actualizado_en = now()
                        WHERE conversacion_id = :conversacionId
                        """)
        .param("conversacionId", conversacionId)
        .param("estado", estado)
        .param("texto", texto)
        .param("queryLogId", queryLogId)
        .update();
  }

  /**
   * Borra la fila: la pregunta quedó esperando que la persona elija una reformulación (ver {@code
   * Consultar.ModoReformulacion.Proponer}), así que no hay respuesta en curso ni terminada que
   * recuperar tras un F5. Dejarla en "en_curso" haría que la UI sondeara para siempre; marcarla
   * "completo" con texto vacío la mostraría como una respuesta en blanco.
   */
  void descartar(long conversacionId) {
    jdbc.sql("DELETE FROM streams_en_curso WHERE conversacion_id = :conversacionId")
        .param("conversacionId", conversacionId)
        .update();
  }

  Optional<Estado> buscar(long conversacionId) {
    return jdbc.sql(
            """
                        SELECT estado, pregunta, project_id, texto, citas, reformulacion, query_log_id
                        FROM streams_en_curso WHERE conversacion_id = :conversacionId
                        """)
        .param("conversacionId", conversacionId)
        .query(
            (rs, n) -> {
              // wasNull() refleja la ULTIMA columna leida -- por eso se lee query_log_id
              // primero y se resuelve su nulidad antes de leer cualquier otra columna,
              // no despues.
              long queryLogId = rs.getLong("query_log_id");
              Long queryLogIdONull = rs.wasNull() ? null : queryLogId;
              return new Estado(
                  rs.getString("estado"),
                  rs.getString("pregunta"),
                  rs.getString("project_id"),
                  rs.getString("texto"),
                  Json.leerCitas(rs.getString("citas")),
                  rs.getString("reformulacion"),
                  queryLogIdONull);
            })
        .optional();
  }
}
