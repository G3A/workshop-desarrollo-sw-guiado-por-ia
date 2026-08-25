package co.g3a.baseconocimiento.teams;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;

import tools.jackson.databind.JsonNode;

/**
 * Sale hacia el Bot Connector API: token de aplicación por
 * {@code client_credentials} (cacheado hasta cerca de su vencimiento),
 * indicador de escritura y la respuesta final como Adaptive Card, ambos vía
 * {@code POST {serviceUrl}/v3/conversations/{id}/activities/{id}}
 * (la operación {@code Conversations_ReplyToActivity} del Connector API).
 *
 * <p>Sin {@code kb.teams.app-id}/{@code app-password} configurados, sale sin
 * token: así funciona igual contra el Bot Framework Emulator sin
 * credenciales, el modo de prueba más común.
 */
@Component
class ClienteConectorBotFramework {

    private static final Logger log = LoggerFactory.getLogger(ClienteConectorBotFramework.class);
    private static final Duration MARGEN_EXPIRACION = Duration.ofSeconds(60);

    private final TeamsPropiedades propiedades;
    private final RestClient restClient = RestClient.create();
    private final AtomicReference<TokenCacheado> tokenCacheado = new AtomicReference<>();

    ClienteConectorBotFramework(TeamsPropiedades propiedades) {
        this.propiedades = propiedades;
    }

    private record TokenCacheado(String valor, Instant expiraEn) {
        boolean vigente() {
            return Instant.now().isBefore(expiraEn.minus(MARGEN_EXPIRACION));
        }
    }

    /** Best-effort: el indicador de escritura es cosmético, nunca debe tumbar la respuesta real. */
    void enviarEscribiendo(Activity entrante) {
        try {
            enviar(entrante, Activity.respuestaA(entrante, Activity.TIPO_ESCRIBIENDO, null, null));
        } catch (Exception e) {
            log.warn("No se pudo enviar el indicador de escritura: {}", e.toString());
        }
    }

    void responderConTarjeta(Activity entrante, Respuesta respuesta) {
        Activity.Attachment tarjeta = TarjetaAdaptativa.desde(respuesta);
        enviar(entrante, Activity.respuestaA(entrante, Activity.TIPO_MENSAJE, List.of(tarjeta), null));
    }

    void responderTexto(Activity entrante, String texto) {
        enviar(entrante, Activity.respuestaA(entrante, Activity.TIPO_MENSAJE, null, texto));
    }

    private void enviar(Activity entrante, Activity saliente) {
        URI url = urlDeRespuesta(entrante);

        var peticion = restClient.post().uri(url).contentType(MediaType.APPLICATION_JSON);
        String token = tokenDeSalida();
        if (token != null) {
            peticion = peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        peticion.body(Json.escribir(saliente)).retrieve().toBodilessEntity();
    }

    private static URI urlDeRespuesta(Activity entrante) {
        String base = entrante.serviceUrl().endsWith("/")
                ? entrante.serviceUrl().substring(0, entrante.serviceUrl().length() - 1)
                : entrante.serviceUrl();
        return UriComponentsBuilder.fromUriString(base)
                .path("/v3/conversations/{conversationId}/activities/{activityId}")
                .build()
                .expand(entrante.conversation().id(), entrante.id())
                .encode()
                .toUri();
    }

    private String tokenDeSalida() {
        if (!propiedades.credencialesConfiguradas()) {
            return null;
        }
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
        formulario.add("client_id", propiedades.appId());
        formulario.add("client_secret", propiedades.appPassword());
        formulario.add("scope", propiedades.oauthScope());

        String respuesta = restClient.post()
                .uri(propiedades.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formulario)
                .retrieve()
                .body(String.class);

        JsonNode nodo = Json.leer(respuesta);
        String accessToken = nodo.get("access_token").asString();
        long expiresInSegundos = nodo.get("expires_in").asLong();
        return new TokenCacheado(accessToken, Instant.now().plusSeconds(expiresInSegundos));
    }
}
