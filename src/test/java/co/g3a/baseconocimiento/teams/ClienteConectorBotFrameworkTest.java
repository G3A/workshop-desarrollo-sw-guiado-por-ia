package co.g3a.baseconocimiento.teams;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.util.List;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;

/**
 * {@link ClienteConectorBotFramework} contra un Bot Connector API doblado con
 * WireMock: verifica el POST de {@code ReplyToActivity} para el indicador de
 * escritura y para la tarjeta, y que el token de salida se pida una sola vez
 * por client_credentials y se reutilice mientras esté vigente.
 */
class ClienteConectorBotFrameworkTest {

    @RegisterExtension
    static WireMockExtension wireMock =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final Activity ACTIVIDAD_BASE = new Activity(
            "message", "activity1", null, null, "test",
            new Activity.ChannelAccount("user1", "Usuario"),
            new Activity.ConversationAccount("conv1"),
            new Activity.ChannelAccount("bot1", "Bot"),
            "pregunta", null, null);

    private Activity actividadEntrante() {
        return new Activity(
                ACTIVIDAD_BASE.type(), ACTIVIDAD_BASE.id(), null, wireMock.baseUrl(), ACTIVIDAD_BASE.channelId(),
                ACTIVIDAD_BASE.from(), ACTIVIDAD_BASE.conversation(), ACTIVIDAD_BASE.recipient(),
                ACTIVIDAD_BASE.text(), null, null);
    }

    private TeamsPropiedades propiedades(String appId, String appPassword) {
        return new TeamsPropiedades(
                true, false, appId, appPassword,
                wireMock.baseUrl() + "/canal", wireMock.baseUrl() + "/emulador",
                wireMock.baseUrl() + "/token", "scope-de-prueba");
    }

    @Test
    void enviaElIndicadorDeEscrituraSinAutorizacionCuandoNoHayCredenciales() {
        wireMock.stubFor(post(urlEqualTo("/v3/conversations/conv1/activities/activity1"))
                .willReturn(aResponse().withStatus(200)));

        var conector = new ClienteConectorBotFramework(propiedades("", ""));
        conector.enviarEscribiendo(actividadEntrante());

        wireMock.verify(postRequestedFor(urlEqualTo("/v3/conversations/conv1/activities/activity1"))
                .withHeader("Authorization", absent())
                .withRequestBody(containing("\"type\":\"typing\"")));
    }

    @Test
    void responderConTarjetaEnviaElAdaptiveCardComoAttachment() {
        wireMock.stubFor(post(urlEqualTo("/v3/conversations/conv1/activities/activity1"))
                .willReturn(aResponse().withStatus(200)));
        Respuesta respuesta = new Respuesta(
                "la respuesta", List.of(new Cita("file:///doc1", "Doc 1", "extracto", "doc_section")), List.of(), 10,
                null);

        var conector = new ClienteConectorBotFramework(propiedades("", ""));
        conector.responderConTarjeta(actividadEntrante(), respuesta);

        wireMock.verify(postRequestedFor(urlEqualTo("/v3/conversations/conv1/activities/activity1"))
                .withRequestBody(containing("\"type\":\"message\""))
                .withRequestBody(containing("application/vnd.microsoft.card.adaptive"))
                .withRequestBody(containing("la respuesta")));
    }

    @Test
    void pideElTokenDeSalidaUnaSolaVezYLoReutiliza() {
        wireMock.stubFor(post(urlEqualTo("/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok-de-prueba\",\"expires_in\":3600}")));
        wireMock.stubFor(post(urlEqualTo("/v3/conversations/conv1/activities/activity1"))
                .willReturn(aResponse().withStatus(200)));

        var conector = new ClienteConectorBotFramework(propiedades("app-id", "app-password"));
        Activity entrante = actividadEntrante();
        conector.enviarEscribiendo(entrante);
        conector.responderTexto(entrante, "listo");

        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=app-id")));
        wireMock.verify(exactly(2), postRequestedFor(urlEqualTo("/v3/conversations/conv1/activities/activity1"))
                .withHeader("Authorization", equalTo("Bearer tok-de-prueba")));
    }
}
