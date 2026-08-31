package co.g3a.baseconocimiento.orquestacion;

import static org.assertj.core.api.Assertions.assertThat;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.llm.Planificador.PlanDeHerramientas;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * El SQL propio de F3 contra un PostgreSQL real: los agregados de {@code recent_commits} y {@code
 * subsystem_index}, la expansion de contexto por vecinos, y el registro en {@code query_log}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "kb.ingesta.worker.habilitado=false",
      "kb.recuperacion.terminos.habilitado=false"
    })
@Testcontainers
class OrquestacionRepositoriosTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg18-trixie")
              .asCompatibleSubstituteFor("postgres"));

  @Autowired JdbcClient jdbc;

  @Autowired HerramientasRepositorio herramientasRepo;

  @Autowired ContextoRepositorio contextoRepo;

  @Autowired QueryLogRepositorio queryLogRepo;

  @Autowired QueryFeedbackRepositorio queryFeedbackRepo;

  @Test
  @DisplayName("recent_commits: solo trae chunks de una fuente local_git, mas recientes primero")
  void masRecientesPorFuente() {
    long fuenteGit = crearFuente("local_git", "repo-1");
    long fuenteDocs = crearFuente("local_docs", "docs-1");
    insertarChunk(fuenteGit, "commit-1", "primer commit", "{}", 0);
    insertarChunk(fuenteDocs, "doc-1", "un documento", "{}", 0);

    List<Fragmento> resultados = herramientasRepo.masRecientesPorFuente("local_git", "default", 10);

    assertThat(resultados).hasSize(1);
    assertThat(resultados.get(0).uri()).contains("commit-1");
  }

  @Test
  @DisplayName("subsystem_index: agrega distilled.systems_mentioned por sistema")
  void indiceDeSubsistemas() {
    long fuente = crearFuente("local_docs", "docs-2");
    insertarChunk(fuente, "s1", "texto", "{\"systems_mentioned\": [\"auth\", \"billing\"]}", 0);
    insertarChunk(fuente, "s2", "texto", "{\"systems_mentioned\": [\"auth\"]}", 0);

    List<Fragmento> indice = herramientasRepo.indiceDeSubsistemas("default", 10);

    assertThat(indice)
        .extracting(Fragmento::titulo)
        .contains("Subsistema: auth", "Subsistema: billing");
    Fragmento auth =
        indice.stream()
            .filter(f -> f.titulo().equals("Subsistema: auth"))
            .findFirst()
            .orElseThrow();
    assertThat(auth.texto()).contains("2 fragmentos");
  }

  @Test
  @DisplayName("contarChunks: cuenta solo los chunks del proyecto pedido")
  void contarChunksDelProyecto() {
    // Sin limpieza entre pruebas de esta clase, "default" acumula chunks de
    // las demas -- por eso se compara antes/despues en vez de un valor fijo.
    long antes = herramientasRepo.contarChunks("default");

    long fuente = crearFuente("local_docs", "docs-conteo");
    insertarChunk(fuente, "c1", "texto uno", "{}", 0);
    insertarChunk(fuente, "c2", "texto dos", "{}", 0);

    assertThat(herramientasRepo.contarChunks("default")).isEqualTo(antes + 2);
    assertThat(herramientasRepo.contarChunks("otro-proyecto-inexistente")).isEqualTo(0L);
  }

  @Test
  @DisplayName("Expansion de contexto: trae el vecino anterior y el siguiente por ord")
  void vecinosPorOrd() {
    long fuente = crearFuente("local_docs", "docs-3");
    long documentoId = crearDocumento(fuente, "doc-vecinos");
    insertarChunkEnDocumento(documentoId, fuente, 0, "seccion cero");
    insertarChunkEnDocumento(documentoId, fuente, 1, "seccion uno");
    insertarChunkEnDocumento(documentoId, fuente, 2, "seccion dos");

    List<ContextoRepositorio.Vecino> vecinos = contextoRepo.vecinos(documentoId, 1);

    assertThat(vecinos)
        .extracting(ContextoRepositorio.Vecino::texto)
        .containsExactly("seccion cero", "seccion dos");
  }

  @Test
  @DisplayName("query_log: registra la traza de la consulta y devuelve un id")
  void registraLaConsulta() {
    Fragmento fragmento =
        new Fragmento(
            1L,
            1L,
            "file:///doc1",
            "Doc 1",
            "texto",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.05,
            8.0);
    var plan = new PlanDeHerramientas(List.of("search_unified"), "porque si");
    var ejecucion =
        new Executor.EjecucionHerramienta("search_unified", List.of(fragmento), 12L, null);
    var cita = new Cita("file:///doc1", "Doc 1", "texto", "doc_section");

    long id =
        queryLogRepo.registrar(
            "¿como se despliega?",
            "default",
            plan,
            List.of(ejecucion),
            List.of(fragmento),
            "Se despliega con docker compose. [1]",
            List.of(cita),
            123L);

    assertThat(id).isPositive();

    String pregunta =
        jdbc.sql("SELECT question FROM query_log WHERE id = :id")
            .param("id", id)
            .query(String.class)
            .single();
    assertThat(pregunta).isEqualTo("¿como se despliega?");
  }

  @Test
  @DisplayName(
      "query_feedback: registra una fila por queryLogId, existe() distingue presente/ausente")
  void registraFeedbackYVerificaExistencia() {
    long queryLogId = registrarQueryLogDePrueba();

    assertThat(queryFeedbackRepo.existe(queryLogId)).isTrue();
    assertThat(queryFeedbackRepo.existe(queryLogId + 999_999L)).isFalse();

    long feedbackId = queryFeedbackRepo.registrar(queryLogId, false, "la cita no aplicaba");
    assertThat(feedbackId).isPositive();

    String comentario =
        jdbc.sql("SELECT comentario FROM query_feedback WHERE id = :id")
            .param("id", feedbackId)
            .query(String.class)
            .single();
    assertThat(comentario).isEqualTo("la cita no aplicaba");
  }

  @Test
  @DisplayName("query_feedback: listarRecientes respeta el limite y ordena por creado_en DESC")
  void listarRecientesRespetaElLimite() {
    long queryLogId = registrarQueryLogDePrueba();
    queryFeedbackRepo.registrar(queryLogId, true, null);
    queryFeedbackRepo.registrar(queryLogId, false, "segundo");

    List<QueryFeedbackRepositorio.FeedbackRegistrado> recientes =
        queryFeedbackRepo.listarRecientes(1);

    assertThat(recientes).hasSize(1);
    assertThat(recientes.get(0).comentario()).isEqualTo("segundo");
  }

  private long registrarQueryLogDePrueba() {
    var plan = new PlanDeHerramientas(List.of("search_unified"), "porque si");
    return queryLogRepo.registrar(
        "¿como se despliega?", "default", plan, List.of(), List.of(), "Respuesta.", List.of(), 10L);
  }

  private long crearFuente(String kind, String nombre) {
    return jdbc.sql(
            """
                        INSERT INTO sources (kind, name) VALUES (:kind, :nombre) RETURNING id
                        """)
        .param("kind", kind)
        .param("nombre", nombre)
        .query(Long.class)
        .single();
  }

  private long crearDocumento(long fuenteId, String externalId) {
    return jdbc.sql(
            """
                        INSERT INTO documents (source_id, external_id, uri, title, raw_text, content_hash)
                        VALUES (:fuenteId, :e, 'file:///' || :e, :e, 'crudo', :e)
                        RETURNING id
                        """)
        .param("fuenteId", fuenteId)
        .param("e", externalId)
        .query(Long.class)
        .single();
  }

  private void insertarChunk(
      long fuenteId, String externalId, String texto, String distilledJson, int ord) {
    long documentoId = crearDocumento(fuenteId, externalId);
    insertarChunkEnDocumento(documentoId, fuenteId, ord, texto, distilledJson);
  }

  private void insertarChunkEnDocumento(long documentoId, long fuenteId, int ord, String texto) {
    insertarChunkEnDocumento(documentoId, fuenteId, ord, texto, "{}");
  }

  private void insertarChunkEnDocumento(
      long documentoId, long fuenteId, int ord, String texto, String distilledJson) {
    jdbc.sql(
            """
                        INSERT INTO chunks (document_id, source_id, ord, kind, text, distilled)
                        VALUES (:documentoId, :fuenteId, :ord, 'doc_section', :texto, CAST(:distilled AS jsonb))
                        """)
        .param("documentoId", documentoId)
        .param("fuenteId", fuenteId)
        .param("ord", ord)
        .param("texto", texto)
        .param("distilled", distilledJson)
        .update();
  }
}
