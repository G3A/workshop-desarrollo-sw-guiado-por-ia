package co.g3a.baseconocimiento.orquestacion;

import static org.assertj.core.api.Assertions.assertThat;

import co.g3a.baseconocimiento.llm.Planificador.PlanDeHerramientas;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Regresión del bug real que encontró la revisión de código de la PR del issue #3: {@code
 * rs.wasNull()} solo refleja la última columna leída, así que hay que resolver la nulidad de {@code
 * query_log_id} antes de leer cualquier otra columna del mismo {@code ResultSet}, no después. Sin
 * este test, ese error compilaba y pasaba el resto de la suite sin avisar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class StreamsEnCursoRepositorioTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg18-trixie")
              .asCompatibleSubstituteFor("postgres"));

  @Autowired StreamsEnCursoRepositorio streamsEnCurso;

  @Autowired QueryLogRepositorio queryLog;

  @Test
  @DisplayName(
      "buscar: queryLogId presente y reformulacion NULL -- wasNull() no debe confundirse con la"
          + " ultima columna leida")
  void queryLogIdPresenteConReformulacionNula() {
    long conversacionId = 1001L;
    long queryLogId = registrarQueryLogDePrueba();
    streamsEnCurso.iniciar(conversacionId, "¿como se despliega?", "default");
    // reformulacion queda NULL a proposito (nunca se llama actualizarCitas).
    streamsEnCurso.finalizar(conversacionId, "completo", "Respuesta final.", queryLogId);

    Optional<StreamsEnCursoRepositorio.Estado> estado = streamsEnCurso.buscar(conversacionId);

    assertThat(estado).isPresent();
    assertThat(estado.get().queryLogId()).isEqualTo(queryLogId);
  }

  @Test
  @DisplayName(
      "buscar: queryLogId NULL (camino de error) y reformulacion no nula -- no debe devolver 0"
          + " como si fuera un id real")
  void queryLogIdNuloConReformulacionPresente() {
    long conversacionId = 1002L;
    streamsEnCurso.iniciar(conversacionId, "¿como se despliega?", "default");
    streamsEnCurso.actualizarCitas(conversacionId, List.of(), "reformulacion no vacia");
    streamsEnCurso.finalizar(conversacionId, "error", "", null);

    Optional<StreamsEnCursoRepositorio.Estado> estado = streamsEnCurso.buscar(conversacionId);

    assertThat(estado).isPresent();
    assertThat(estado.get().queryLogId()).isNull();
  }

  private long registrarQueryLogDePrueba() {
    var plan = new PlanDeHerramientas(List.of("search_unified"), "porque si");
    return queryLog.registrar(
        "¿como se despliega?", "default", plan, List.of(), List.of(), "Respuesta.", List.of(), 10L);
  }
}
