package co.g3a.baseconocimiento.modelos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Prueba de humo del cross-encoder real: carga el ONNX y el tokenizador que {@code make
 * pull-models} descarga, y verifica que distingue un pasaje relevante de uno irrelevante.
 *
 * <p>Se salta sola si los archivos no están (máquinas de desarrollo que aún no corrieron {@code
 * make pull-models}) — no es un test que deba bloquear a quien recién clona el repo.
 */
class RerankerOnnxTest {

  private static final Path RUTA = Path.of("./.data/models/reranker");

  private static RerankerOnnx reranker;

  @BeforeAll
  static void cargar() {
    assumeTrue(
        Files.exists(RUTA.resolve("model.onnx")) && Files.exists(RUTA.resolve("tokenizer.json")),
        "Reranker no descargado; corre `make pull-models` para incluir esta prueba");
    reranker = new RerankerOnnx(RUTA.toString());
  }

  @AfterAll
  static void cerrar() throws Exception {
    if (reranker != null) {
      reranker.close();
    }
  }

  @Test
  void distingueUnPasajeRelevanteDeUnoIrrelevante() {
    String consulta = "¿Cómo se despliega el servicio?";
    String pasajeRelevante =
        "Para desplegar el servicio corre docker compose up y luego make pull-models.";
    String pasajeIrrelevante = "El gato duerme en el jardín durante las tardes de verano.";

    double puntajeRelevante = reranker.puntuar(consulta, pasajeRelevante);
    double puntajeIrrelevante = reranker.puntuar(consulta, pasajeIrrelevante);

    assertThat(puntajeRelevante)
        .as("un pasaje que responde la pregunta debe puntuar alto")
        .isGreaterThan(puntajeIrrelevante);
    assertThat(puntajeRelevante).isBetween(0.0, 10.0);
    assertThat(puntajeIrrelevante).isBetween(0.0, 10.0);
  }
}
