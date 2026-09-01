package co.g3a.baseconocimiento.ingesta;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code kb.azdo.*}: interruptor del conector, organización/proyecto, el PAT para autenticación
 * Basic y el identificador de la wiki a ingerir. {@code base-url} solo se sobreescribe en pruebas,
 * para apuntar a un WireMock local en vez de a {@code dev.azure.com} real.
 */
@ConfigurationProperties(prefix = "kb.azdo")
record AzureDevOpsPropiedades(
    boolean habilitado, String org, String proyecto, String pat, String wiki, String baseUrl) {}
