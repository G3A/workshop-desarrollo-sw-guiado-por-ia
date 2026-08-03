package co.g3a.baseconocimiento;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Base de Conocimiento interna: recuperacion hibrida sobre documentos, codigo,
 * hilos de Teams y work items, con dos adaptadores (UI web y bot de Teams).
 *
 * <p>La arquitectura reproduce la del articulo de Cerebras: destilacion por LLM
 * antes de indexar, una unica tabla de embeddings, cuatro senales fusionadas por
 * RRF y un cross-encoder antes de sintetizar.
 */
@Modulithic(systemName = "base-conocimiento")
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class BaseConocimientoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BaseConocimientoApplication.class, args);
    }
}
