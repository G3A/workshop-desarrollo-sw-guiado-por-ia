package co.g3a.baseconocimiento.seguridad;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code kb.api-token}: vacio (el default) deja el API sin autenticacion; con un valor, {@link
 * ApiTokenFilter} exige {@code Authorization: Bearer <token>} en los endpoints programaticos.
 *
 * <p>Registrado via {@code @ConfigurationPropertiesScan} en {@code BaseConocimientoApplication},
 * sin {@code @Component} — mismo patron que {@code TeamsPropiedades}.
 */
@ConfigurationProperties(prefix = "kb")
record SeguridadPropiedades(String apiToken) {

  boolean habilitada() {
    return apiToken != null && !apiToken.isBlank();
  }
}
