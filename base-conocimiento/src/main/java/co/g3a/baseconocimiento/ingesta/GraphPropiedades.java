package co.g3a.baseconocimiento.ingesta;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code kb.graph.*}: interruptor del conector de Teams por Graph, credenciales de la app
 * registrada, el canal a ingerir y las URLs base de Graph/Azure AD — estas últimas solo se
 * sobreescriben en pruebas, para apuntar a un WireMock local en vez de a Microsoft real.
 */
@ConfigurationProperties(prefix = "kb.graph")
record GraphPropiedades(
    boolean habilitado,
    String tenantId,
    String clientId,
    String clientSecret,
    String teamId,
    String channelId,
    String graphBaseUrl,
    String loginBaseUrl) {}
