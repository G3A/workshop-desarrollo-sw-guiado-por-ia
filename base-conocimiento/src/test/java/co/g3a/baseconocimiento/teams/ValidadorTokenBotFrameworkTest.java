package co.g3a.baseconocimiento.teams;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Gate de riesgo del plan (sección Pruebas automatizadas de F5): "validación del JWT de Bot
 * Connector con un JWKS de prueba, incluyendo tokens vencidos y con audiencia equivocada". WireMock
 * dobla tanto el documento OpenID como el JWKS; los tokens se firman de verdad con una clave RSA
 * generada en el test.
 */
class ValidadorTokenBotFrameworkTest {

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private static final String APP_ID = "app-de-prueba";
  private static final String SERVICE_URL = "https://smba.trafficmanager.net/amer/";
  private static final String ISSUER_EMULATOR_V2 =
      "https://login.microsoftonline.com/d6d49420-f39b-4df7-a1dc-d59a935871db/v2.0";

  private RSAKey claves;

  @BeforeEach
  void generarClavesYPublicarJwks() throws Exception {
    KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
    generador.initialize(2048);
    KeyPair par = generador.generateKeyPair();
    claves =
        new RSAKey.Builder((RSAPublicKey) par.getPublic())
            .privateKey((RSAPrivateKey) par.getPrivate())
            .keyID("kid-de-prueba")
            .build();

    wireMock.stubFor(
        get(urlEqualTo("/jwks"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(new JWKSet(claves.toPublicJWK()).toString())));
    wireMock.stubFor(
        get(urlEqualTo("/canal"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"jwks_uri\":\"" + wireMock.baseUrl() + "/jwks\"}")));
    wireMock.stubFor(
        get(urlEqualTo("/emulador"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"jwks_uri\":\"" + wireMock.baseUrl() + "/jwks\"}")));
  }

  private TeamsPropiedades propiedades(boolean emulator) {
    return new TeamsPropiedades(
        true,
        emulator,
        APP_ID,
        "secreto",
        wireMock.baseUrl() + "/canal",
        wireMock.baseUrl() + "/emulador",
        wireMock.baseUrl() + "/token",
        "https://api.botframework.com/.default");
  }

  private String tokenDeCanal(String audiencia, String serviceUrl, Instant expiracion)
      throws Exception {
    return firmar(
        new JWTClaimsSet.Builder()
            .issuer(ValidadorTokenBotFramework.ISSUER_CANAL)
            .audience(audiencia)
            .claim("serviceurl", serviceUrl)
            .expirationTime(Date.from(expiracion))
            .issueTime(Date.from(Instant.now().minusSeconds(60)))
            .build());
  }

  private String tokenDeEmuladorV2(String azp, String serviceUrl) throws Exception {
    return firmar(
        new JWTClaimsSet.Builder()
            .issuer(ISSUER_EMULATOR_V2)
            .claim("ver", "2.0")
            .claim("azp", azp)
            .claim("serviceurl", serviceUrl)
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .issueTime(Date.from(Instant.now().minusSeconds(60)))
            .build());
  }

  private String firmar(JWTClaimsSet claims) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(claves.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(claves));
    return jwt.serialize();
  }

  @Test
  void aceptaUnTokenDeCanalValido() throws Exception {
    var validador = new ValidadorTokenBotFramework(propiedades(false));
    String token = tokenDeCanal(APP_ID, SERVICE_URL, Instant.now().plusSeconds(300));

    assertThatCode(() -> validador.validar("Bearer " + token, SERVICE_URL))
        .doesNotThrowAnyException();
  }

  @Test
  void rechazaUnTokenVencido() throws Exception {
    var validador = new ValidadorTokenBotFramework(propiedades(false));
    String token = tokenDeCanal(APP_ID, SERVICE_URL, Instant.now().minusSeconds(3600));

    assertThatThrownBy(() -> validador.validar("Bearer " + token, SERVICE_URL))
        .isInstanceOf(ValidadorTokenBotFramework.TokenInvalidoException.class);
  }

  @Test
  void rechazaUnaAudienciaEquivocada() throws Exception {
    var validador = new ValidadorTokenBotFramework(propiedades(false));
    String token = tokenDeCanal("otra-app-distinta", SERVICE_URL, Instant.now().plusSeconds(300));

    assertThatThrownBy(() -> validador.validar("Bearer " + token, SERVICE_URL))
        .isInstanceOf(ValidadorTokenBotFramework.TokenInvalidoException.class);
  }

  @Test
  void rechazaUnServiceUrlDistintoAlDeLaActivityEntrante() throws Exception {
    var validador = new ValidadorTokenBotFramework(propiedades(false));
    String token = tokenDeCanal(APP_ID, SERVICE_URL, Instant.now().plusSeconds(300));

    assertThatThrownBy(
            () -> validador.validar("Bearer " + token, "https://otro-service-url.example.com/"))
        .isInstanceOf(ValidadorTokenBotFramework.TokenInvalidoException.class);
  }

  @Test
  void aceptaUnTokenDeEmuladorV2ConAzp() throws Exception {
    var validador = new ValidadorTokenBotFramework(propiedades(true));
    String token = tokenDeEmuladorV2(APP_ID, SERVICE_URL);

    assertThatCode(() -> validador.validar("Bearer " + token, SERVICE_URL))
        .doesNotThrowAnyException();
  }

  @Test
  void sinCredencialesConfiguradasNoValidaNada() {
    var propiedadesSinAppId =
        new TeamsPropiedades(
            true,
            false,
            "",
            "",
            wireMock.baseUrl() + "/canal",
            wireMock.baseUrl() + "/emulador",
            wireMock.baseUrl() + "/token",
            "scope");
    var validador = new ValidadorTokenBotFramework(propiedadesSinAppId);

    assertThatCode(() -> validador.validar(null, SERVICE_URL)).doesNotThrowAnyException();
  }
}
