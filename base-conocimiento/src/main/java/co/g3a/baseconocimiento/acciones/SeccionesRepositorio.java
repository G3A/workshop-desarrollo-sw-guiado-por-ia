package co.g3a.baseconocimiento.acciones;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las secciones completas de los documentos que la persona eligio, en el orden en que fueron
 * troceadas ({@code ord}). No es retrieval: no hay consulta contra la cual rankear, la seleccion la
 * hizo la persona a mano y eso es todo lo que una accion necesita.
 *
 * <p>Filtra por {@code project_id} igual que todo lo demas: un id de otro proyecto simplemente no
 * aparece (la segmentacion multi-tenant del MVP se decide en SQL, antes de tocar el LLM), y quien
 * llama lo reporta como no indexado en vez de esconderlo.
 */
@Repository
class SeccionesRepositorio {

  /**
   * Un chunk tal como esta en la base. {@code tipo} es {@code chunks.kind}: la traduccion deja
   * pasar los {@code code_block} sin tocarlos.
   */
  record Seccion(
      long id, long documentoId, String uri, String titulo, int ord, String texto, String tipo) {}

  private final JdbcClient jdbc;

  SeccionesRepositorio(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Ordenadas por documento (id) y luego por {@code ord}; el orden entre documentos que eligio la
   * persona lo restituye quien llama. Vacio si ninguno existe, y sin ir a la base si la lista viene
   * vacia.
   */
  List<Seccion> seccionesDe(List<Long> documentoIds, String projectId) {
    if (documentoIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT c.id, c.document_id, d.uri, d.title, c.ord, c.text, c.kind
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE c.document_id IN (:documentoIds) AND c.project_id = :projectId
            ORDER BY c.document_id, c.ord
            """)
        .param("documentoIds", documentoIds)
        .param("projectId", projectId)
        .query(
            (rs, n) ->
                new Seccion(
                    rs.getLong("id"),
                    rs.getLong("document_id"),
                    rs.getString("uri"),
                    rs.getString("title"),
                    rs.getInt("ord"),
                    rs.getString("text"),
                    rs.getString("kind")))
        .list();
  }
}
