package co.g3a.baseconocimiento.teams;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Valida el JWT que llega en el header {@code Authorization} de
 * {@code POST /api/messages} contra el JWKS de Bot Framework.
 *
 * <p>La lógica está portada de {@code ChannelValidation}/{@code EmulatorValidation}
 * del Bot Framework SDK para Java (archivado en enero de 2026, ver el plan):
 * dos documentos OpenID distintos según el modo, dos formas de leer el appId
 * del token (audience directa para canal real; {@code appid} o {@code azp}
 * según la versión del token para el Emulator, porque Azure AD v1 y v2 lo
 * exponen distinto), y la misma verificación final del claim
 * {@code serviceurl} contra la Activity recién recibida — sin esta última
 * comprobación, un token robado de otra conversación serviría igual aquí.
 * Constantes verificadas contra el código fuente actual de
 * {@code AuthenticationConstants.java}, no contra memoria.
 */
@Component
class ValidadorTokenBotFramework {

    private static final Logger log = LoggerFactory.getLogger(ValidadorTokenBotFramework.class);

    static final String ISSUER_CANAL = "https://api.botframework.com";

    /**
     * Los 6 issuers que Azure AD emite para el Emulator: dos GUID de tenant
     * (el tenant público {@code botframework.com} y su equivalente de US
     * Government), cada uno en v1 ({@code sts.windows.net}) y v2
     * ({@code login.microsoftonline.com}/{@code .us}).
     */
    static final Set<String> ISSUERS_EMULATOR = Set.of(
            "https://sts.windows.net/d6d49420-f39b-4df7-a1dc-d59a935871db/",
            "https://login.microsoftonline.com/d6d49420-f39b-4df7-a1dc-d59a935871db/v2.0",
            "https://sts.windows.net/f8cdef31-a31e-4b4a-93e4-5f571e91255a/",
            "https://login.microsoftonline.com/f8cdef31-a31e-4b4a-93e4-5f571e91255a/v2.0",
            "https://sts.windows.net/cab8a31a-1906-4287-a0d8-4eef66b95f6e/",
            "https://login.microsoftonline.us/cab8a31a-1906-4287-a0d8-4eef66b95f6e/v2.0");

    private static final String CLAIM_SERVICE_URL = "serviceurl";
    private static final String CLAIM_APPID = "appid";
    private static final String CLAIM_AZP = "azp";
    private static final String CLAIM_VERSION = "ver";
    private static final String VERSION_2 = "2.0";

    private final TeamsPropiedades propiedades;
    private final RestClient restClient = RestClient.create();
    private final ConcurrentHashMap<String, JwtDecoder> decodersPorMetadata = new ConcurrentHashMap<>();

    ValidadorTokenBotFramework(TeamsPropiedades propiedades) {
        this.propiedades = propiedades;
    }

    static final class TokenInvalidoException extends RuntimeException {
        TokenInvalidoException(String mensaje) {
            super(mensaje);
        }

        TokenInvalidoException(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /**
     * No lanza nada si {@code kb.teams.app-id} está vacío: sin credenciales
     * configuradas el producto se demuestra completo igual (ver Supuestos del
     * plan) y no hay contra qué credencial validar.
     */
    void validar(String encabezadoAutorizacion, String serviceUrlEntrante) {
        if (!propiedades.credencialesConfiguradas()) {
            return;
        }
        if (encabezadoAutorizacion == null || !encabezadoAutorizacion.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new TokenInvalidoException("Falta el header Authorization Bearer");
        }
        String token = encabezadoAutorizacion.substring(7).trim();

        Jwt jwt;
        try {
            jwt = decoder().decode(token);
        } catch (JwtException e) {
            throw new TokenInvalidoException("Firma o vigencia invalida: " + e.getMessage(), e);
        }

        validarEmisorYAudiencia(jwt);

        String serviceUrlDelToken = jwt.getClaimAsString(CLAIM_SERVICE_URL);
        if (serviceUrlDelToken == null || !serviceUrlDelToken.equalsIgnoreCase(serviceUrlEntrante)) {
            throw new TokenInvalidoException("El claim serviceurl del token no coincide con la Activity entrante");
        }
    }

    private void validarEmisorYAudiencia(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        if (propiedades.emulator()) {
            if (!ISSUERS_EMULATOR.contains(issuer)) {
                throw new TokenInvalidoException("Issuer de Emulator no reconocido: " + issuer);
            }
            // El Emulator, segun la version del token, manda el appId por
            // "appid" (v1) o por Authorized Party "azp" (v2) -- v1 y v2 de
            // Azure AD exponen esta identidad en claims distintos.
            String version = jwt.getClaimAsString(CLAIM_VERSION);
            String appIdDelToken =
                    VERSION_2.equals(version) ? jwt.getClaimAsString(CLAIM_AZP) : jwt.getClaimAsString(CLAIM_APPID);
            if (appIdDelToken == null || !appIdDelToken.equals(propiedades.appId())) {
                throw new TokenInvalidoException("El appId del Emulator no coincide con kb.teams.app-id");
            }
        } else {
            if (!ISSUER_CANAL.equals(issuer)) {
                throw new TokenInvalidoException("Issuer de canal invalido: " + issuer);
            }
            if (jwt.getAudience() == null || !jwt.getAudience().contains(propiedades.appId())) {
                throw new TokenInvalidoException("El audience del token no coincide con kb.teams.app-id");
            }
        }
    }

    private JwtDecoder decoder() {
        String metadataUrl = propiedades.emulator() ? propiedades.emulatorMetadataUrl() : propiedades.channelMetadataUrl();
        return decodersPorMetadata.computeIfAbsent(metadataUrl, this::construirDecoder);
    }

    private JwtDecoder construirDecoder(String metadataUrl) {
        String jwksUri = resolverJwksUri(metadataUrl);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        // Solo vigencia aqui: issuer y audiencia se verifican a mano arriba,
        // porque cada modo los lee de un claim distinto (ver validarEmisorYAudiencia).
        OAuth2TokenValidator<Jwt> validadorVigencia = new JwtTimestampValidator(Duration.ofMinutes(5));
        decoder.setJwtValidator(validadorVigencia);
        log.info("Decoder de JWT de Bot Framework construido contra {} (jwks_uri={})", metadataUrl, jwksUri);
        return decoder;
    }

    private String resolverJwksUri(String metadataUrl) {
        String cuerpo = restClient.get().uri(metadataUrl).retrieve().body(String.class);
        JsonNode nodo = Json.leer(cuerpo).get("jwks_uri");
        if (nodo == null || nodo.isNull() || nodo.isMissingNode()) {
            throw new IllegalStateException("El documento OpenID de " + metadataUrl + " no trae jwks_uri");
        }
        return nodo.asString();
    }
}
