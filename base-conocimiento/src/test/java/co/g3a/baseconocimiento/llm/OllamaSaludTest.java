package co.g3a.baseconocimiento.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Los dos fallos que hacian que {@code make health} mintiera sobre los modelos que faltan.
 *
 * <p>Los dos son falsos NEGATIVOS -- decir "no falta nada" cuando si falta -- que es la peor
 * direccion posible para este indicador: existe justo para avisar ANTES de que la primera consulta
 * reviente, y callarse lo deja peor que no existir, porque da una confianza que no se ha ganado.
 *
 * <p>Ollama se dobla con WireMock, como el resto de los clientes HTTP del repo.
 */
class OllamaSaludTest {

  @RegisterExtension
  static WireMockExtension ollama =
      WireMockExtension.newInstance()
          .options(
              com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig()
                  .dynamicPort())
          .build();

  /** granite4.1 esta descargado, pero en 8b -- NO en el 3b que pide el perfil. */
  private static final String TAGS =
      "{\"models\":[{\"name\":\"gemma3:4b\"},{\"name\":\"bge-m3:latest\"},"
          + "{\"name\":\"granite4.1:8b\"}]}";

  /**
   * El bug que reporto un usuario: con {@code make up-granite41} y el modelo sin descargar, {@code
   * make health} decia "faltantes: ninguna". El indicador comprobaba {@code
   * kb.llm.destilador-modelo} -- el del destilador de Teams -- y no {@code KB_LLM_MODELO}, que es
   * el que sirve el chat en los nueve perfiles de Ollama.
   */
  @Test
  @DisplayName("El modelo del chat (KB_LLM_MODELO) se comprueba: si no esta, sale como faltante")
  void reportaElModeloDeChatAusente() {
    Health salud = salud("granite4.1:3b", "gemma3:4b", "bge-m3");

    assertThat(faltantes(salud)).containsExactly("granite4.1:3b");
    assertThat(salud.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("Con todo descargado no se reporta nada faltante")
  void noReportaNadaCuandoEstaTodo() {
    assertThat(faltantes(salud("gemma3:4b", "gemma3:4b", "bge-m3"))).isEmpty();
  }

  /**
   * El segundo bug: {@code coincide} caia a comparar el nombre base (lo anterior al {@code :}), asi
   * que cualquier etiqueta valia por cualquier otra. La etiqueta es justo lo que distingue un
   * modelo que cabe en la tarjeta de uno que no.
   */
  @Test
  @DisplayName("Una etiqueta distinta NO cuenta como presente: 8b descargado no cubre a 3b")
  void laEtiquetaNoSeIgnora() {
    assertThat(faltantes(salud("granite4.1:3b", "gemma3:4b", "bge-m3"))).contains("granite4.1:3b");
  }

  @Test
  @DisplayName("Un requerido sin etiqueta equivale al :latest, que es la convencion de Ollama")
  void sinEtiquetaEquivaleALatest() {
    // bge-m3 se pide sin etiqueta y Ollama lo reporta como bge-m3:latest.
    assertThat(faltantes(salud("gemma3:4b", "gemma3:4b", "bge-m3"))).doesNotContain("bge-m3");
  }

  @Test
  @DisplayName("Un modelo de chat vacio no se cuenta como requerido")
  void modeloDeChatVacioSeIgnora() {
    assertThat(faltantes(salud("", "gemma3:4b", "bge-m3"))).isEmpty();
  }

  @Test
  @DisplayName("Si Ollama no responde, el indicador baja a DOWN")
  void ollamaCaidoEsDown() {
    var indicador = new OllamaSalud("http://localhost:1", "gemma3:4b", "gemma3:4b", "bge-m3");

    assertThat(indicador.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @SuppressWarnings("unchecked")
  private static List<String> faltantes(Health salud) {
    return (List<String>) salud.getDetails().get("modelosFaltantes");
  }

  private static Health salud(String modeloChat, String modeloDestilador, String modeloEmbeddings) {
    ollama.stubFor(
        get(urlEqualTo("/api/tags"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(TAGS)));
    return new OllamaSalud(ollama.baseUrl(), modeloChat, modeloDestilador, modeloEmbeddings)
        .health();
  }
}
