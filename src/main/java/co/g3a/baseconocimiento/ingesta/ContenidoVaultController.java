package co.g3a.baseconocimiento.ingesta;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sirve el archivo detrás de una {@code Cita} para el visor modal de la página de
 * chat: {@code file:///vault/...} es una URI válida para mostrarle al usuario de
 * dónde salió el fragmento, pero el navegador no puede abrirla (ni el esquema
 * {@code file://} navega desde una página {@code http}, ni esa ruta existe fuera
 * del contenedor) — este endpoint traduce esa URI al archivo real y lo devuelve
 * por HTTP, inline para previsualizar o como adjunto para descargar.
 *
 * <p>Vive en {@code ingesta}, no en {@code web}, por la misma razón que
 * {@link AdminController}: necesita conocer {@code kb.ingesta.vault-dir}, y
 * {@code ArquitecturaTest} no deja que {@code web} dependa de {@code ingesta}.
 * Sin token igual que {@code /api/admin/documentos} — lo llama la página de chat
 * sin sesión ni token.
 *
 * <p>"Solo lectura sobre archivos ya indexados" no es solo un comentario: se
 * exige con {@link IngestaRepositorio#existeDocumentoConUri}, no basta con que
 * la uri resuelva a un archivo real dentro de {@code vaultDir} — sin ese
 * chequeo, cualquiera que adivine la convención de nombres de
 * {@link ConectorDocumentosLocales}/{@link ConectorReposLocales} podría leer
 * archivos que nunca fueron parte de una cita (basura de docling, archivos
 * rechazados, etc.), no solo los que el corpus realmente expone.
 */
@RestController
class ContenidoVaultController {

    // MediaTypeFactory (mime.types de Apache, la que trae Spring) no conoce
    // ".md": sin este mapa la vista previa de la mayoria de los documentos del
    // vault (la extension mas comun de ConectorDocumentosLocales) cae en
    // application/octet-stream y el navegador ofrece descargar en vez de mostrar
    // el texto.
    private static final Map<String, MediaType> TIPOS_SIN_MAPEO_ESTANDAR = Map.of(
            "md", MediaType.valueOf("text/markdown"),
            "markdown", MediaType.valueOf("text/markdown"));

    private final Path vaultDir;
    private final IngestaRepositorio repo;

    ContenidoVaultController(@Value("${kb.ingesta.vault-dir}") String vaultDir, IngestaRepositorio repo) {
        this.vaultDir = Path.of(vaultDir).normalize();
        this.repo = repo;
    }

    @GetMapping("/api/vault/contenido")
    ResponseEntity<Resource> contenido(
            @RequestParam String uri, @RequestParam(defaultValue = "false") boolean descargar) {
        Path archivo = resolverDesdeUri(uri);
        // Orden a proposito: si el archivo ni siquiera resuelve dentro del vault
        // (uri externa, path traversal) o no existe, ni vale la pena la
        // consulta a la base para el chequeo de "esta indexado de verdad".
        if (archivo == null || !Files.isRegularFile(archivo) || !repo.existeDocumentoConUri(uri)) {
            return ResponseEntity.notFound().build();
        }

        Resource recurso = new FileSystemResource(archivo);
        MediaType tipo = tipoDe(archivo, recurso);
        ContentDisposition disposicion = (descargar ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(archivo.getFileName().toString())
                .build();

        return ResponseEntity.ok()
                .contentType(tipo)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposicion.toString())
                .body(recurso);
    }

    private static MediaType tipoDe(Path archivo, Resource recurso) {
        String nombre = archivo.getFileName().toString();
        int puntoFinal = nombre.lastIndexOf('.');
        String extension = puntoFinal < 0 ? "" : nombre.substring(puntoFinal + 1).toLowerCase(Locale.ROOT);
        return Optional.ofNullable(TIPOS_SIN_MAPEO_ESTANDAR.get(extension))
                .or(() -> MediaTypeFactory.getMediaType(recurso))
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    /**
     * {@code null} si la uri no viene del vault o si, tras resolverla, cae afuera de
     * {@code vaultDir} (incluido vía symlink) — ver {@link RutasVault}.
     */
    private Path resolverDesdeUri(String uriCruda) {
        if (uriCruda == null || !uriCruda.startsWith(VaultUri.PREFIJO)) {
            return null;
        }
        String relativo = uriCruda.substring(VaultUri.PREFIJO.length());
        return RutasVault.resolverDentroDe(vaultDir, relativo).orElse(null);
    }
}
