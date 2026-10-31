package co.g3a.baseconocimiento.llm;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Reporta si las dos instancias de {@code llama-server} responden: la del LLM y la de embeddings
 * (ADR-0017). Corren fuera de Docker, como procesos del host, asi que nada en compose las vigila:
 * este indicador es el unico lugar donde la api dice que le falta una.
 *
 * <p>Reemplaza a {@code OllamaSalud}, que listaba los modelos descargados en Ollama y nombraba los
 * faltantes. Con llama.cpp no hay catalogo que consultar: cada instancia sirve el GGUF con el que
 * arranco, y si el archivo falta no arranca. Lo que queda por distinguir es si el proceso esta
 * arriba, si todavia carga el modelo ({@code /health} responde 503 mientras tanto) o si no hay
 * nadie escuchando.
 *
 * <p>DOWN en cuanto una de las dos no esta lista: sin LLM no hay respuestas, y sin embeddings no
 * hay ni busqueda vectorial ni ingesta. El detalle dice cual falla y con que comando se levanta.
 */
@Component("llamaCpp")
class LlamaCppSalud implements HealthIndicator {

  private static final Logger log = LoggerFactory.getLogger(LlamaCppSalud.class);

  static final String ACCION = "make llm-up  (y make llm-estado para ver el log de cada instancia)";

  private final RestClient cliente;
  private final Map<String, String> instancias = new LinkedHashMap<>();

  LlamaCppSalud(
      @Value("${spring.ai.openai.base-url:http://localhost:8081}") String urlLlm,
      @Value("${spring.ai.openai.embedding.base-url:http://localhost:8082}") String urlEmbeddings) {
    instancias.put("llm", raiz(urlLlm));
    instancias.put("embeddings", raiz(urlEmbeddings));

    var fabrica = new SimpleClientHttpRequestFactory();
    fabrica.setConnectTimeout(Duration.ofSeconds(3));
    fabrica.setReadTimeout(Duration.ofSeconds(5));
    this.cliente = RestClient.builder().requestFactory(fabrica).build();
  }

  @Override
  public Health health() {
    var detalle = new LinkedHashMap<String, Object>();
    boolean todasListas = true;
    for (var instancia : instancias.entrySet()) {
      String estado = estadoDe(instancia.getValue());
      todasListas &= "listo".equals(estado);
      detalle.put(instancia.getKey(), Map.of("url", instancia.getValue(), "estado", estado));
    }
    if (todasListas) {
      return Health.up().withDetails(detalle).withDetail("accion", "ninguna").build();
    }
    log.warn("llama-server no esta listo: {}. Accion: {}", detalle, ACCION);
    return Health.down().withDetails(detalle).withDetail("accion", ACCION).build();
  }

  private String estadoDe(String url) {
    try {
      cliente.get().uri(url + "/health").retrieve().toBodilessEntity();
      return "listo";
    } catch (HttpStatusCodeException e) {
      return e.getStatusCode().value() == 503
          ? "cargando el modelo"
          : "responde " + e.getStatusCode().value();
    } catch (Exception e) {
      return "no responde";
    }
  }

  /**
   * La URL base de Spring AI lleva {@code /v1} (la API compatible con OpenAI), pero {@code /health}
   * vive en la raiz del servidor.
   */
  static String raiz(String url) {
    String sinBarra = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    return sinBarra.endsWith("/v1") ? sinBarra.substring(0, sinBarra.length() - 3) : sinBarra;
  }
}
