package co.g3a.baseconocimiento.acciones;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /**
   * La ruta de encabezados de cada seccion ({@code "Capitulo › Seccion"}), para agrupar por
   * capitulo (issue #57). Desde #57 la ingesta la guarda en {@code distilled.ruta}; para lo
   * indexado antes se saca del {@code summary}. Sin ruta, lista vacia.
   */
  Map<Long, List<String>> rutasDe(List<Long> documentoIds, String projectId) {
    if (documentoIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, List<String>> rutas = new HashMap<>();
    jdbc.sql(
            """
            SELECT c.id,
                   CASE WHEN jsonb_typeof(c.distilled->'ruta') = 'array'
                        THEN array_to_string(
                               ARRAY(SELECT jsonb_array_elements_text(c.distilled->'ruta')), ' › ')
                   END AS ruta,
                   c.distilled->>'summary' AS summary
            FROM chunks c
            WHERE c.document_id IN (:documentoIds) AND c.project_id = :projectId
            """)
        .param("documentoIds", documentoIds)
        .param("projectId", projectId)
        .query(
            rs -> {
              String guardada = rs.getString("ruta");
              List<String> ruta =
                  guardada == null
                      ? Capitulos.rutaDelSummary(rs.getString("summary"))
                      : guardada.isEmpty() ? List.of() : List.of(guardada.split(" › "));
              rutas.put(rs.getLong("id"), ruta);
            });
    return rutas;
  }

  /**
   * El embedding de cada seccion que ya lo tiene, para elegir las representativas del modo «Rapido»
   * (issue #57). Las que el worker todavia no proceso no aparecen.
   */
  Map<Long, float[]> embeddingsDe(List<Long> documentoIds, String projectId) {
    if (documentoIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, float[]> vectores = new HashMap<>();
    jdbc.sql(
            """
            SELECT c.id, c.embedding::text AS embedding
            FROM chunks c
            WHERE c.document_id IN (:documentoIds) AND c.project_id = :projectId
              AND c.embedding IS NOT NULL
            """)
        .param("documentoIds", documentoIds)
        .param("projectId", projectId)
        .query(
            rs -> {
              vectores.put(rs.getLong("id"), vector(rs.getString("embedding")));
            });
    return vectores;
  }

  /** {@code "[0.1,0.2,...]"}, la forma de texto de pgvector. */
  static float[] vector(String texto) {
    String[] partes = texto.substring(1, texto.length() - 1).split(",");
    float[] v = new float[partes.length];
    for (int i = 0; i < partes.length; i++) {
      v[i] = Float.parseFloat(partes[i]);
    }
    return v;
  }
}
