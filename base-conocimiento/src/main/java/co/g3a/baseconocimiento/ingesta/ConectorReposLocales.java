package co.g3a.baseconocimiento.ingesta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;

/**
 * Ingiere repos Git locales bajo {@code <vault>/repos}: cada subcarpeta con
 * un {@code .git} es una fuente propia ({@code local_git}), con su propio
 * último SHA guardado en {@code sources.sync_state}.
 *
 * <p>Incremental a nivel de repo: si el HEAD no cambió desde la última
 * corrida, el repo entero se salta sin tocar disco. Si cambió, la
 * incrementalidad por archivo es la misma que {@link ConectorDocumentosLocales}
 * ya usa — comparar el hash del contenido — en vez de recorrer el diff de
 * cada commit con JGit: mucho más simple y, a esta escala, igual de barato.
 */
@Component
class ConectorReposLocales {

    private static final Logger log = LoggerFactory.getLogger(ConectorReposLocales.class);
    private static final String KIND = "local_git";
    private static final int LONGITUD_RESUMEN = 280;
    private static final Set<String> EXTENSIONES = Set.of(
            "java", "kt", "py", "js", "jsx", "ts", "tsx", "go", "rb", "cs", "c", "h", "cpp", "hpp", "rs", "php");
    private static final Set<String> CARPETAS_IGNORADAS =
            Set.of(".git", "target", "build", "dist", "node_modules", ".idea", ".vscode", "__pycache__");

    private final IngestaRepositorio repo;
    private final Path raiz;

    ConectorReposLocales(IngestaRepositorio repo, @Value("${kb.ingesta.vault-dir}") String vaultDir) {
        this.repo = repo;
        this.raiz = Path.of(vaultDir).resolve("repos");
    }

    record Resumen(int reposVistos, int reposActualizados, int reposSinCambios, int chunksCreados) {
    }

    Resumen ingerir() {
        if (!Files.isDirectory(raiz)) {
            log.warn("El directorio de repos {} no existe; nada que ingerir", raiz);
            return new Resumen(0, 0, 0, 0);
        }

        List<Path> repos = listarRepos();
        int actualizados = 0;
        int sinCambios = 0;
        int chunksCreados = 0;

        for (Path repoPath : repos) {
            String nombreRepo = repoPath.getFileName().toString();
            long sourceId = repo.obtenerOCrearFuente(KIND, nombreRepo);

            try (Git git = Git.open(repoPath.toFile())) {
                ObjectId head = git.getRepository().resolve("HEAD");
                String shaActual = head == null ? "" : head.getName();
                String shaPrevio = repo.obtenerSyncState(sourceId)
                        .map(Json::leer)
                        .map(nodo -> nodo.get("last_sha"))
                        .filter(n -> n != null && !n.isNull())
                        .map(n -> n.asString())
                        .orElse(null);

                if (shaActual.equals(shaPrevio)) {
                    sinCambios++;
                    continue;
                }

                chunksCreados += ingerirArchivos(sourceId, repoPath, nombreRepo);
                repo.actualizarSyncState(sourceId, Json.escribir(Map.of("last_sha", shaActual)));
                actualizados++;
            } catch (IOException e) {
                log.warn("No se pudo abrir el repo {}: {}", repoPath, e.toString());
            }
        }

        log.info("Ingesta de repos locales: {} vistos, {} actualizados, {} sin cambios, {} chunks nuevos",
                repos.size(), actualizados, sinCambios, chunksCreados);
        return new Resumen(repos.size(), actualizados, sinCambios, chunksCreados);
    }

    private int ingerirArchivos(long sourceId, Path repoPath, String nombreRepo) {
        List<String> vistos = new ArrayList<>();
        int chunksCreados = 0;

        for (Path archivo : listarArchivosElegibles(repoPath)) {
            String externalId = repoPath.relativize(archivo).toString().replace('\\', '/');
            vistos.add(externalId);

            byte[] bytes = leer(archivo);
            String hash = sha256Hex(bytes);
            repo.marcarArchivoDetectado(sourceId, externalId, bytes.length);

            Optional<IngestaRepositorio.DocumentoExistente> existente = repo.buscarDocumento(sourceId, externalId);
            if (existente.isPresent() && existente.get().contentHash().equals(hash)) {
                continue;
            }

            try {
                String texto = new String(bytes, StandardCharsets.UTF_8);
                long documentoId = repo.upsertDocumento(sourceId, externalId,
                        VaultUri.deRepo(nombreRepo, externalId), externalId, texto, hash,
                        ProyectoId.POR_DEFECTO.valor());
                repo.marcarArchivoProcesado(sourceId, externalId, documentoId);

                int ord = 0;
                for (ChunkerCodigo.Bloque bloque : ChunkerCodigo.trocear(texto)) {
                    String distilled = Json.escribir(Map.of("summary", resumenDe(bloque)));
                    long chunkId = repo.insertarChunk(documentoId, sourceId, ProyectoId.POR_DEFECTO.valor(), ord++,
                            "code_block", bloque.cuerpo(), distilled);
                    repo.encolarEmbeberChunk(chunkId);
                    chunksCreados++;
                }
            } catch (RuntimeException e) {
                // Mismo aislamiento de fallos que ConectorDocumentosLocales: un
                // archivo problematico no debe tumbar el resto del repo, sigue
                // "visto" (no se borra como huerfano) y se reintenta en el
                // proximo relevo sin hash actualizado.
                repo.marcarArchivoError(sourceId, externalId, e.getMessage());
                log.warn("No se pudo ingerir {} de {}: {}", externalId, nombreRepo, e.getMessage());
            }
        }

        for (long huerfano : repo.documentosHuerfanos(sourceId, vistos)) {
            repo.eliminarDocumento(huerfano);
        }
        repo.eliminarArchivosVaultHuerfanos(sourceId, vistos);
        return chunksCreados;
    }

    private static String resumenDe(ChunkerCodigo.Bloque bloque) {
        String vista = bloque.cuerpo().replaceAll("\\s+", " ").strip();
        if (vista.length() > LONGITUD_RESUMEN) {
            vista = vista.substring(0, LONGITUD_RESUMEN) + "…";
        }
        return bloque.ruta() == null ? vista : bloque.ruta() + ": " + vista;
    }

    private List<Path> listarRepos() {
        try (var stream = Files.list(raiz)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> Files.isDirectory(p.resolve(".git")))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Fallo escaneando " + raiz, e);
        }
    }

    private List<Path> listarArchivosElegibles(Path repoPath) {
        try (var stream = Files.walk(repoPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> CARPETAS_IGNORADAS.stream()
                            .noneMatch(c -> p.toString().contains(java.io.File.separator + c + java.io.File.separator)))
                    .filter(p -> extension(p).map(EXTENSIONES::contains).orElse(false))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Fallo escaneando " + repoPath, e);
        }
    }

    private static Optional<String> extension(Path archivo) {
        String nombre = archivo.getFileName().toString();
        int i = nombre.lastIndexOf('.');
        return i < 0 ? Optional.empty() : Optional.of(nombre.substring(i + 1).toLowerCase(Locale.ROOT));
    }

    private static byte[] leer(Path archivo) {
        try {
            return Files.readAllBytes(archivo);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + archivo, e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
