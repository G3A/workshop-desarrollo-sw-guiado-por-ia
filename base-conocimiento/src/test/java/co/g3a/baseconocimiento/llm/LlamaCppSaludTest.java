package co.g3a.baseconocimiento.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * El indicador de las dos instancias de {@code llama-server} (ADR-0017). Cada una se dobla con
 * WireMock, como el resto de los clientes HTTP del repo.
 */
class LlamaCppSaludTest {

  @RegisterExtension
  static WireMockExtension llm =
      WireMockExtension.newInstance()
          .options(
              com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig()
                  .dynamicPort())
          .build();

  @RegisterExtension
  static WireMockExtension embeddings =
      WireMockExtension.newInstance()
          .options(
              com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig()
                  .dynamicPort())
          .build();

  @Test
  @DisplayName("Con las dos instancias listas el indicador queda UP y sin accion")
  void lasDosListasEsUp() {
    responder(llm, 200);
    responder(embeddings, 200);

    Health salud = salud();

    assertThat(salud.getStatus()).isEqualTo(Status.UP);
    assertThat(estado(salud, "llm")).isEqualTo("listo");
    assertThat(estado(salud, "embeddings")).isEqualTo("listo");
    assertThat(salud.getDetails().get("accion")).isEqualTo("ninguna");
  }

  @Test
  @DisplayName("Sin la instancia de embeddings queda DOWN y dice cual falta y como levantarla")
  void sinEmbeddingsEsDown() {
    responder(llm, 200);
    var indicador = new LlamaCppSalud(llm.baseUrl() + "/v1", "http://localhost:1/v1");

    Health salud = indicador.health();

    assertThat(salud.getStatus()).isEqualTo(Status.DOWN);
    assertThat(estado(salud, "llm")).isEqualTo("listo");
    assertThat(estado(salud, "embeddings")).isEqualTo("no responde");
    assertThat(salud.getDetails().get("accion")).isEqualTo(LlamaCppSalud.ACCION);
  }

  /**
   * llama-server responde 503 en /health mientras carga el GGUF: no es lo mismo que estar caido.
   */
  @Test
  @DisplayName("Un 503 de /health se reporta como 'cargando el modelo', no como caido")
  void cargandoElModeloNoEsCaido() {
    responder(llm, 503);
    responder(embeddings, 200);

    Health salud = salud();

    assertThat(salud.getStatus()).isEqualTo(Status.DOWN);
    assertThat(estado(salud, "llm")).isEqualTo("cargando el modelo");
  }

  @Test
  @DisplayName("El /v1 de la URL de Spring AI se quita para llegar a /health en la raiz")
  void laRaizQuitaElV1() {
    assertThat(LlamaCppSalud.raiz("http://host.docker.internal:8081/v1"))
        .isEqualTo("http://host.docker.internal:8081");
    assertThat(LlamaCppSalud.raiz("http://localhost:8082/v1/")).isEqualTo("http://localhost:8082");
    assertThat(LlamaCppSalud.raiz("http://localhost:8082")).isEqualTo("http://localhost:8082");
  }

  private static void responder(WireMockExtension servidor, int codigo) {
    servidor.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(codigo)));
  }

  private static Health salud() {
    return new LlamaCppSalud(llm.baseUrl() + "/v1", embeddings.baseUrl() + "/v1").health();
  }

  @SuppressWarnings("unchecked")
  private static Object estado(Health salud, String instancia) {
    return ((Map<String, Object>) salud.getDetails().get(instancia)).get("estado");
  }
}
