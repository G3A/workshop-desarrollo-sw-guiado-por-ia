package co.g3a.baseconocimiento.teams;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code kb.teams.*}: interruptor del adaptador, modo Emulator y credenciales de la app de Azure
 * Bot, más las URLs del protocolo Bot Connector — estas últimas solo se sobreescriben en pruebas,
 * para apuntar a un WireMock local en vez de a login.botframework.com real.
 *
 * <p>Registrado vía {@code @ConfigurationPropertiesScan} en {@code BaseConocimientoApplication},
 * sin {@code @Component} — mismo patrón que {@code RecuperacionPropiedades}.
 */
@ConfigurationProperties(prefix = "kb.teams")
record TeamsPropiedades(
    boolean habilitado,
    boolean emulator,
    String appId,
    String appPassword,
    String channelMetadataUrl,
    String emulatorMetadataUrl,
    String tokenUrl,
    String oauthScope) {

  boolean credencialesConfiguradas() {
    return appId != null && !appId.isBlank();
  }
}
