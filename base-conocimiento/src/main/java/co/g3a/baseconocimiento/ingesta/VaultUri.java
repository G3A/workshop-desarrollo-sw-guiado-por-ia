package co.g3a.baseconocimiento.ingesta;

/**
 * Única fuente de verdad para el esquema {@code file:///vault/...} que identifica, en una cita, un
 * archivo que vive dentro del contenedor. Antes estaba repetido en {@code
 * ConectorDocumentosLocales}, {@code ConectorReposLocales} y {@code ContenidoVaultController}, cada
 * uno con su propio literal.
 *
 * <p>OJO: {@code app.js} (función {@code esUriDelVault}) tiene su propia copia de {@link #PREFIJO}
 * — no hay forma de compartir una constante entre Java y JS, así que un cambio acá exige el mismo
 * cambio ahí.
 */
final class VaultUri {

  static final String PREFIJO = "file:///vault/";

  private VaultUri() {}

  static String deDocumentoLocal(String externalId) {
    return PREFIJO + "documentos/" + externalId;
  }

  static String deRepo(String nombreRepo, String externalId) {
    return PREFIJO + "repos/" + nombreRepo + "/" + externalId;
  }
}
