package co.g3a.baseconocimiento.ingesta;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Cliente HTTP hacia Microsoft Graph: token de aplicación por {@code client_credentials} (cacheado
 * hasta cerca de su vencimiento, mismo patrón que {@code ClienteConectorBotFramework} del adaptador
 * de Teams) y un GET autenticado genérico.
 */
@Component
class ClienteGraph {

  private static final Duration MARGEN_EXPIRACION = Duration.ofSeconds(60);
  private static final String SCOPE = "https://graph.microsoft.com/.default";

  private final GraphPropiedades propiedades;
  private final RestClient restClient = RestClient.create();
  private final AtomicReference<TokenCacheado> tokenCacheado = new AtomicReference<>();

  ClienteGraph(GraphPropiedades propiedades) {
    this.propiedades = propiedades;
  }

  private record TokenCacheado(String valor, Instant expiraEn) {
    boolean vigente() {
      return Instant.now().isBefore(expiraEn.minus(MARGEN_EXPIRACION));
    }
  }

  /**
   * @param urlAbsolutaOPath un path relativo a {@code graph-base-url}, o una URL absoluta como un
   *     {@code @odata.nextLink}/{@code deltaLink}
   */
  String get(String urlAbsolutaOPath) {
    String url =
        urlAbsolutaOPath.startsWith("http")
            ? urlAbsolutaOPath
            : propiedades.graphBaseUrl() + urlAbsolutaOPath;
    return restClient
        .get()
        .uri(url)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
        .retrieve()
        .body(String.class);
  }

  private String token() {
    TokenCacheado actual = tokenCacheado.get();
    if (actual != null && actual.vigente()) {
      return actual.valor();
    }
    TokenCacheado nuevo = pedirToken();
    tokenCacheado.set(nuevo);
    return nuevo.valor();
  }

  private TokenCacheado pedirToken() {
    MultiValueMap<String, String> formulario = new LinkedMultiValueMap<>();
    formulario.add("grant_type", "client_credentials");
    formulario.add("client_id", propiedades.clientId());
    formulario.add("client_secret", propiedades.clientSecret());
    formulario.add("scope", SCOPE);

    String url = propiedades.loginBaseUrl() + "/" + propiedades.tenantId() + "/oauth2/v2.0/token";
    String respuesta =
        restClient
            .post()
            .uri(url)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(formulario)
            .retrieve()
            .body(String.class);

    JsonNode nodo = Json.leer(respuesta);
    return new TokenCacheado(
        nodo.get("access_token").asString(),
        Instant.now().plusSeconds(nodo.get("expires_in").asLong()));
  }
}
