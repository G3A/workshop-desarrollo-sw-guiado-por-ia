package co.g3a.baseconocimiento.recuperacion;

import static org.assertj.core.api.Assertions.assertThat;

import co.g3a.baseconocimiento.modelos.Embeddings;
import com.pgvector.PGvector;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Las cuatro señales contra un PostgreSQL real, más el recálculo de {@code term_stats} que alimenta
 * la señal 3. No prueba el reranker (eso pide el ONNX real descargado por {@code make pull-models},
 * ver {@code RerankerOnnxTest}) — solo el SQL de {@link RecuperacionRepositorio}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "kb.ingesta.worker.habilitado=false",
      "kb.recuperacion.terminos.habilitado=false"
    })
@Testcontainers
class RecuperacionRepositorioTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg18-trixie")
              .asCompatibleSubstituteFor("postgres"));

  @Autowired JdbcClient jdbc;

  @Autowired RecuperacionRepositorio repositorio;

  @Test
  @DisplayName("Senal 1 (texto completo): encuentra por la pregunta destilada")
  void senalFts() {
    long id1 =
        insertarChunk(
            "fts-1",
            "texto crudo irrelevante",
            "{\"searchable_question\":\"como se despliega el servicio\"}",
            null);
    insertarChunk("fts-2", "otro texto sin relacion con nada", "{}", null);

    List<CandidatoSenal> resultados =
        repositorio.buscarPorFts("como se despliega", "default", List.of(), List.of(), 10);

    assertThat(resultados).extracting(CandidatoSenal::chunkId).contains(id1);
  }

  @Test
  @DisplayName("El filtro de tipos excluye chunks de un kind distinto")
  void filtroDeTipos() {
    long doc =
        insertarChunk(
            "tipo-doc",
            "contenido de documentacion sobre despliegue",
            "{\"summary\":\"contenido de documentacion sobre despliegue\"}",
            null);
    long codigo =
        insertarChunkConTipo(
            "tipo-code",
            "contenido de documentacion sobre despliegue",
            "{\"summary\":\"contenido de documentacion sobre despliegue\"}",
            "code_block");

    List<CandidatoSenal> soloDocs =
        repositorio.buscarPorFts(
            "documentacion despliegue", "default", List.of("doc_section"), List.of(), 10);

    assertThat(soloDocs).extracting(CandidatoSenal::chunkId).contains(doc).doesNotContain(codigo);
  }

  @Test
  @DisplayName(
      "El filtro de documentos excluye chunks de un documento no permitido "
          + "(F11: activar/desactivar documentos por conversacion)")
  void filtroDeDocumentos() {
    long permitido =
        insertarChunk(
            "doc-permitido",
            "contenido sobre despliegue del servicio",
            "{\"summary\":\"contenido sobre despliegue del servicio\"}",
            null);
    long excluido =
        insertarChunk(
            "doc-excluido",
            "contenido sobre despliegue del servicio",
            "{\"summary\":\"contenido sobre despliegue del servicio\"}",
            null);
    long documentoPermitidoId = documentIdDe(permitido);

    List<CandidatoSenal> soloPermitido =
        repositorio.buscarPorFts(
            "despliegue servicio", "default", List.of(), List.of(documentoPermitidoId), 10);

    assertThat(soloPermitido)
        .extracting(CandidatoSenal::chunkId)
        .contains(permitido)
        .doesNotContain(excluido);
  }

  @Test
  @DisplayName("Senal 2 (densa): ordena por cercania coseno al embedding de la consulta")
  void senalVector() {
    long cercano = insertarChunk("vec-1", "cercano", "{}", vectorUnitario(0));
    long lejano = insertarChunk("vec-2", "lejano", "{}", vectorUnitario(1));

    List<CandidatoSenal> resultados =
        repositorio.buscarPorVector(vectorUnitario(0), "default", List.of(), List.of(), 10);

    assertThat(resultados).extracting(CandidatoSenal::chunkId).contains(cercano, lejano);
    assertThat(indiceDe(resultados, cercano))
        .as("el embedding identico al de la consulta debe rankear antes que el ortogonal")
        .isLessThan(indiceDe(resultados, lejano));
  }

  @Test
  @DisplayName(
      "Senal 3 (IDF): tras recalcular term_stats, un termino raro pesa mas que uno de relleno")
  void senalIdf() {
    // "desplegar" aparece en un solo chunk (informativo, IDF alto).
    // "sistema" aparece en los otros tres (relleno, IDF bajo).
    long informativo =
        insertarChunk(
            "idf-1", "", "{\"summary\":\"para desplegar el servicio corre el script\"}", null);
    insertarChunk(
        "idf-2", "", "{\"summary\":\"el sistema registra cada evento del sistema\"}", null);
    insertarChunk(
        "idf-3", "", "{\"summary\":\"el sistema arranca junto con el sistema operativo\"}", null);
    insertarChunk("idf-4", "", "{\"summary\":\"el sistema expone metricas del sistema\"}", null);

    repositorio.recalcularEstadisticasTerminos();

    List<CandidatoSenal> resultados =
        repositorio.buscarPorIdf("desplegar sistema", "default", List.of(), List.of(), 10);

    assertThat(resultados).isNotEmpty();
    assertThat(resultados.get(0).chunkId())
        .as("el chunk con el termino raro debe superar a los que solo repiten el termino comun")
        .isEqualTo(informativo);
  }

  @Test
  @DisplayName("Senal 4 (decaimiento): el chunk mas reciente rankea antes que el mas viejo")
  void senalDecaimiento() {
    long viejo = insertarChunk("dec-1", "viejo", "{}", null);
    jdbc.sql("UPDATE chunks SET source_updated_at = now() - interval '10 days' WHERE id = :id")
        .param("id", viejo)
        .update();
    long reciente = insertarChunk("dec-2", "reciente", "{}", null);

    List<CandidatoSenal> resultados =
        repositorio.buscarPorDecaimiento("default", List.of(), List.of(), 30.0, 10);

    assertThat(indiceDe(resultados, reciente))
        .as("30 dias de vida media: a los 10 dias el reciente debe seguir rankeando antes")
        .isLessThan(indiceDe(resultados, viejo));
  }

  private static int indiceDe(List<CandidatoSenal> resultados, long chunkId) {
    for (int i = 0; i < resultados.size(); i++) {
      if (resultados.get(i).chunkId() == chunkId) {
        return i;
      }
    }
    throw new AssertionError("chunk " + chunkId + " no aparecio en los resultados");
  }

  private long documentIdDe(long chunkId) {
    return jdbc.sql("SELECT document_id FROM chunks WHERE id = :id")
        .param("id", chunkId)
        .query(Long.class)
        .single();
  }

  private static float[] vectorUnitario(int indice) {
    float[] v = new float[Embeddings.DIMENSIONES];
    v[indice] = 1f;
    return v;
  }

  /** Inserta fuente, documento y chunk enlazados; embebe si {@code embedding} no es nulo. */
  private long insertarChunk(
      String externalId, String texto, String destiladoJson, float[] embedding) {
    return insertarChunk(externalId, texto, destiladoJson, embedding, "doc_section");
  }

  private long insertarChunkConTipo(
      String externalId, String texto, String destiladoJson, String tipo) {
    return insertarChunk(externalId, texto, destiladoJson, null, tipo);
  }

  private long insertarChunk(
      String externalId, String texto, String destiladoJson, float[] embedding, String tipo) {
    jdbc.sql("INSERT INTO sources (kind, name) VALUES ('local_docs', :n)")
        .param("n", externalId)
        .update();
    jdbc.sql(
            """
                        INSERT INTO documents (source_id, external_id, uri, title, raw_text, content_hash)
                        SELECT id, :e, 'file:///' || :e, :e, 'crudo', :e FROM sources WHERE name = :e
                        """)
        .param("e", externalId)
        .update();
    long chunkId =
        jdbc.sql(
                """
                        INSERT INTO chunks (document_id, source_id, ord, kind, text, distilled)
                        SELECT d.id, d.source_id, 0, :tipo, :t, CAST(:j AS jsonb)
                        FROM documents d WHERE d.external_id = :e
                        RETURNING id
                        """)
            .param("e", externalId)
            .param("t", texto)
            .param("j", destiladoJson)
            .param("tipo", tipo)
            .query(Long.class)
            .single();

    if (embedding != null) {
      jdbc.sql("UPDATE chunks SET embedding = :embedding WHERE id = :id")
          .param("embedding", new PGvector(embedding))
          .param("id", chunkId)
          .update();
    }
    return chunkId;
  }
}
