package co.g3a.baseconocimiento.ingesta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import co.g3a.baseconocimiento.ingesta.IngestaRepositorio.ConteoEstado;
import co.g3a.baseconocimiento.ingesta.IngestaRepositorio.FuenteAdmin;
import co.g3a.baseconocimiento.ingesta.RelevadorDeFuentes.ResultadoRelevo;

/**
 * La consola de administración de F9: lo que hoy exige terminal
 * ({@code make ingest}, {@code make health}, {@code psql}) pero puede verse
 * y operarse desde el navegador. Vive en {@code ingesta}, no en {@code web},
 * por la misma razón que {@link IngestaController}: es un endpoint de
 * operación del propio módulo, no una puerta de usuario final.
 *
 * <p>Queda detrás de {@code KB_API_TOKEN} cuando está configurado (a
 * diferencia de {@code /api/chat}/{@code /api/preview}): estos endpoints los
 * llama {@code fetch}, no {@code EventSource}, así que sí pueden mandar la
 * cabecera — ver {@code ApiTokenFilter}.
 */
@RestController
class AdminController {

    private final IngestaRepositorio repo;
    private final RelevadorDeFuentes relevador;
    private final Path documentosDir;
    private final Path reposDir;
    private final long relevoIntervaloMs;
    private final boolean relevoHabilitado;
    private final boolean cargaHabilitada;

    AdminController(
            IngestaRepositorio repo, RelevadorDeFuentes relevador,
            @Value("${kb.ingesta.vault-dir}") String vaultDir,
            @Value("${kb.ingesta.relevo.intervalo-ms:900000}") long relevoIntervaloMs,
            @Value("${kb.ingesta.relevo.habilitado:true}") boolean relevoHabilitado,
            @Value("${kb.ingesta.carga-habilitada:false}") boolean cargaHabilitada) {
        this.repo = repo;
        this.relevador = relevador;
        Path raiz = Path.of(vaultDir);
        this.documentosDir = raiz.resolve("documentos");
        this.reposDir = raiz.resolve("repos");
        this.relevoIntervaloMs = relevoIntervaloMs;
        this.relevoHabilitado = relevoHabilitado;
        this.cargaHabilitada = cargaHabilitada;
    }

    record FuenteConEstado(FuenteAdmin fuente, ResultadoRelevo ultimoRelevo) {
    }

    @GetMapping("/api/admin/fuentes")
    List<FuenteConEstado> fuentes() {
        return repo.listarFuentes().stream()
                .map(f -> new FuenteConEstado(f, relevador.ultimoResultado(f.kind()).orElse(null)))
                .toList();
    }

    @PostMapping("/api/admin/fuentes/{tipo}/reindexar")
    ResultadoRelevo reindexar(@PathVariable String tipo) {
        return relevador.relevar(tipo);
    }

    @GetMapping("/api/admin/cola")
    List<ConteoEstado> cola() {
        return repo.contarTrabajosPorEstado();
    }

    /**
     * El estado efectivo se calcula acá, no en SQL: el worker de embeddings
     * ({@code TrabajadorEmbebido}) no sabe nada de archivos, solo de chunks —
     * ver el comentario de {@code vault_archivos.estado} en la migración V3.
     */
    record ArchivoEstado(
            long id, String kind, String fuenteNombre, String externalId, String estado,
            String lastError, long tamanoBytes, Instant actualizadoEn, long chunksTotales, long chunksEmbebidos) {
    }

    @GetMapping("/api/admin/vault/archivos")
    List<ArchivoEstado> archivosVault() {
        return repo.listarArchivosVault().stream()
                .map(a -> new ArchivoEstado(
                        a.id(), a.kind(), a.fuenteNombre(), a.externalId(), estadoEfectivo(a),
                        a.lastError(), a.tamanoBytes(), a.actualizadoEn(), a.chunksTotales(), a.chunksEmbebidos()))
                .toList();
    }

    private static String estadoEfectivo(IngestaRepositorio.ArchivoVaultAdmin a) {
        if (!"procesando".equals(a.estado())) {
            // detectado | extrayendo | error: el estado guardado ya es el efectivo.
            return a.estado();
        }
        if (a.chunksTotales() == 0) {
            return "procesando";
        }
        return a.chunksEmbebidos() < a.chunksTotales()
                ? "embebiendo (" + a.chunksEmbebidos() + "/" + a.chunksTotales() + ")"
                : "listo";
    }

    /**
     * Para el selector de proyecto de F10 (chat) y de la propia consola.
     * Excluido de {@code ApiTokenFilter} igual que {@code /api/admin/ayuda}:
     * la página de chat lo necesita sin sesión ni token.
     */
    @GetMapping("/api/admin/proyectos")
    List<String> proyectos() {
        return repo.listarProyectos();
    }

    /**
     * Para el checklist de "documentos activos" de la conversación en la página
     * de chat — mismo motivo que {@code /api/admin/proyectos} para estar afuera
     * de {@code ApiTokenFilter}: lo llama la página de chat sin sesión ni token.
     */
    @GetMapping("/api/admin/documentos")
    List<IngestaRepositorio.DocumentoResumen> documentos(
            @RequestParam(defaultValue = "default") String projectId) {
        return repo.listarDocumentosLocales(projectId);
    }

    record Ayuda(
            String documentosDir, String reposDir, List<String> extensionesAceptadas,
            long relevoIntervaloMs, boolean relevoHabilitado, boolean cargaHabilitada) {
    }

    /**
     * Lo que el botón {@code ?} de la UI muestra: rutas y valores reales que
     * el servidor está usando, no una convención escrita a mano en el HTML —
     * ver la discusión de {@code KB_VAULT_DIR} vs {@code KB_VAULT_RUTA} en
     * {@code docs/plans/plan-base-conocimiento.md}.
     */
    @GetMapping("/api/admin/ayuda")
    Ayuda ayuda() {
        return new Ayuda(
                documentosDir.toString(), reposDir.toString(),
                ConectorDocumentosLocales.extensionesAceptadas().stream().sorted().toList(),
                relevoIntervaloMs, relevoHabilitado, cargaHabilitada);
    }

    /**
     * Carga desde el navegador, apagada por defecto. Habilitarla
     * ({@code KB_INGESTA_CARGA_HABILITADA=true}) exige además quitar el
     * {@code :ro} del bind mount del vault en {@code compose.yml}: sin eso,
     * el contenedor no puede escribir ahí y esta llamada falla con un error
     * de E/S explícito, no en silencio.
     */
    @PostMapping("/api/admin/vault/documentos")
    ResponseEntity<String> subirArchivo(@RequestParam("archivo") MultipartFile archivo) {
        if (!cargaHabilitada) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Carga deshabilitada (kb.ingesta.carga-habilitada=false)");
        }
        Optional<String> nombreValido = validarNombre(archivo.getOriginalFilename());
        if (nombreValido.isEmpty()) {
            return ResponseEntity.badRequest().body("Nombre de archivo invalido o extension no aceptada");
        }
        Optional<Path> destino = RutasVault.resolverDentroDe(documentosDir, nombreValido.get());
        if (destino.isEmpty()) {
            return ResponseEntity.badRequest().body("Ruta de archivo invalida");
        }
        try {
            Files.createDirectories(documentosDir);
            archivo.transferTo(destino.get());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir " + destino.get(), e);
        }
        return ResponseEntity.ok("Archivo guardado: " + nombreValido.get());
    }

    /**
     * Borra un archivo del vault de verdad: el archivo físico y su documento/chunks
     * indexados. Un borrado que solo tocara la base de datos se revertiría solo en el
     * próximo relevo — {@code marcarArchivoDetectado} vuelve a ver el archivo en disco,
     * sin hash previo con el que compararlo, y lo reingesta como si fuera nuevo. Por
     * eso exige el mismo flag que la carga: sin él el vault está montado {@code :ro} y
     * escribir ahí fallaría con un error de E/S.
     */
    @DeleteMapping("/api/admin/vault/archivos/{id}")
    ResponseEntity<String> eliminarArchivo(@PathVariable long id) {
        if (!cargaHabilitada) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    "Borrado deshabilitado (kb.ingesta.carga-habilitada=false): el vault esta montado de solo lectura, "
                            + "asi que un borrado que solo tocara el indice reaparaceria en el proximo relevo.");
        }
        Optional<IngestaRepositorio.ArchivoVaultParaEliminar> archivo = repo.buscarArchivoVaultParaEliminar(id);
        if (archivo.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!"local_docs".equals(archivo.get().kind())) {
            return ResponseEntity.badRequest().body(
                    "Solo se pueden eliminar archivos de tipo Documentos locales; las demas fuentes se sincronizan solas.");
        }

        String nombre = Path.of(archivo.get().externalId()).getFileName().toString();
        Optional<Path> objetivo = RutasVault.resolverDentroDe(documentosDir, nombre);
        if (objetivo.isEmpty()) {
            return ResponseEntity.badRequest().body("Ruta de archivo invalida");
        }
        try {
            Files.deleteIfExists(objetivo.get());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar " + objetivo.get(), e);
        }

        if (archivo.get().documentId() != null) {
            repo.eliminarDocumento(archivo.get().documentId());
        }
        repo.eliminarArchivoVault(id);

        return ResponseEntity.ok("Archivo eliminado: " + archivo.get().externalId());
    }

    private static Optional<String> validarNombre(String nombreOriginal) {
        if (nombreOriginal == null || nombreOriginal.isBlank()) {
            return Optional.empty();
        }
        String nombre = Path.of(nombreOriginal).getFileName().toString();
        int i = nombre.lastIndexOf('.');
        String extension = i < 0 ? "" : nombre.substring(i + 1).toLowerCase(Locale.ROOT);
        if (!ConectorDocumentosLocales.extensionesAceptadas().contains(extension)) {
            return Optional.empty();
        }
        return Optional.of(nombre);
    }
}
