package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.g3a.baseconocimiento.modelos.Embeddings;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * El worker de embeddings no retiene conexiones a la base mientras espera al modelo. Antes, cada
 * uno de los 25 trabajos del lote abría una transacción que duraba toda la llamada a Ollama: con un
 * pool de 10, la ingesta de un PDF grande moría a los 30 s con "Failed to obtain JDBC Connection"
 * (medido con jls25.pdf, ADR-0014).
 *
 * <p>El pool de esta prueba es de 3 conexiones con 2 s de espera, y el modelo doblado tarda 4 s: si
 * alguna conexión quedara tomada durante el cálculo, la consulta del medio fallaría.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "kb.ingesta.worker.habilitado=true",
      "kb.ingesta.worker.intervalo-ms=3600000",
      "kb.ingesta.relevo.habilitado=false",
      "kb.recuperacion.terminos.habilitado=false",
      "spring.datasource.hikari.maximum-pool-size=3",
      "spring.datasource.hikari.connection-timeout=2000"
    })
@Testcontainers
class TrabajadorEmbebidoProgramadorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg18-trixie")
              .asCompatibleSubstituteFor("postgres"));

  @Autowired TrabajadorEmbebidoProgramador programador;

  @Autowired IngestaRepositorio repo;

  @Autowired JdbcClient jdbc;

  @MockitoBean Embeddings embeddings;

  @BeforeEach
  void colaVacia() {
    jdbc.sql("DELETE FROM ingest_jobs").update();
  }

  @Test
  @DisplayName("Mientras el modelo calcula, la base sigue atendiendo: no hay conexiones retenidas")
  void noRetieneConexionesMientrasElModeloCalcula() throws Exception {
    encolarChunks(6);
    when(embeddings.embeber(any()))
        .thenAnswer(
            inv -> {
              Thread.sleep(4_000);
              return new float[Embeddings.DIMENSIONES];
            });

    var lote = CompletableFuture.runAsync(programador::ejecutarLote);
    Thread.sleep(1_000);

    // Es lo que hace la ingesta mientras el worker trabaja: insertar chunks y encolarlos.
    assertThat(jdbc.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);

    lote.join();
    assertThat(trabajosEn("done")).isEqualTo(6);
  }

  @Test
  @DisplayName("Un trabajo que quedó en running por una caída se retoma cuando vence su plazo")
  void retomaUnTrabajoAbandonado() {
    encolarChunks(1);
    jdbc.sql("UPDATE ingest_jobs SET status = 'running', locked_at = now() - interval '1 hour'")
        .update();
    when(embeddings.embeber(any())).thenReturn(new float[Embeddings.DIMENSIONES]);

    programador.ejecutarLote();

    assertThat(trabajosEn("done")).isEqualTo(1);
  }

  @Test
  @DisplayName("Un trabajo en running dentro de su plazo no se toma dos veces")
  void noTomaUnTrabajoQueOtroEstaProcesando() {
    encolarChunks(1);
    jdbc.sql("UPDATE ingest_jobs SET status = 'running', locked_at = now()").update();
    when(embeddings.embeber(any())).thenReturn(new float[Embeddings.DIMENSIONES]);

    programador.ejecutarLote();

    assertThat(trabajosEn("running")).isEqualTo(1);
    assertThat(trabajosEn("done")).isZero();
  }

  private void encolarChunks(int n) {
    long fuente = repo.obtenerOCrearFuente("local_docs", "prueba-worker");
    long documento =
        repo.upsertDocumento(
            fuente,
            "doc-" + System.nanoTime(),
            "vault://prueba",
            "prueba",
            "texto",
            "hash-" + System.nanoTime(),
            "default");
    for (int i = 0; i < n; i++) {
      long chunk =
          repo.insertarChunk(
              documento,
              fuente,
              "default",
              i,
              "doc_section",
              "cuerpo " + i,
              "{\"summary\": \"resumen " + i + "\"}");
      repo.encolarEmbeberChunk(chunk);
    }
  }

  private int trabajosEn(String estado) {
    return jdbc.sql("SELECT count(*) FROM ingest_jobs WHERE status = :estado")
        .param("estado", estado)
        .query(Integer.class)
        .single();
  }
}
