package co.g3a.baseconocimiento.modelos;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reporta si los archivos del reranker estan presentes, sin cargarlos.
 *
 * <p>Cargar el modelo (~550 MB) en cada chequeo de salud seria absurdo; esto solo mira el disco. El
 * primer {@code puntuar()} real es quien paga la carga, una sola vez, con el candado en {@link
 * RerankerOnnx}.
 */
@Component("reranker")
class RerankerSalud implements HealthIndicator {

  private final Path directorioModelo;

  RerankerSalud(@Value("${kb.reranker.ruta}") String ruta) {
    this.directorioModelo = Path.of(ruta);
  }

  @Override
  public Health health() {
    boolean modelo = Files.exists(directorioModelo.resolve("model.onnx"));
    boolean tokenizador = Files.exists(directorioModelo.resolve("tokenizer.json"));

    // UP en ambos casos: igual que con Ollama, faltar el archivo no debe
    // dejar el contenedor enfermo antes del primer `make pull-models`.
    return Health.up()
        .withDetail("ruta", directorioModelo.toString())
        .withDetail("modeloPresente", modelo)
        .withDetail("tokenizadorPresente", tokenizador)
        .withDetail("accion", (modelo && tokenizador) ? "ninguna" : "corre `make pull-models`")
        .build();
  }
}
