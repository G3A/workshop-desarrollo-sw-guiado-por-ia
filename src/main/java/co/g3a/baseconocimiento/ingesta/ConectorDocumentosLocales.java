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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;

/**
 * Ingiere {@code ./corpus}: Markdown y texto por encabezados, PDF por ventanas
 * de párrafos. Es el conector que garantiza que el producto sea demostrable sin
 * ninguna credencial de terceros.
 */
@Component
class ConectorDocumentosLocales {

    private static final Logger log = LoggerFactory.getLogger(ConectorDocumentosLocales.class);
    private static final String KIND = "local_docs";
    private static final String NOMBRE_FUENTE = "corpus";
    private static final Set<String> EXTENSIONES = Set.of("md", "markdown", "txt", "pdf");
    private static final int MAX_CARACTERES_VENTANA_PDF = 1_500;
    private static final int LONGITUD_RESUMEN = 280;

    private final IngestaRepositorio repo;
    private final ExtractorPdf extractorPdf;
    private final Path raiz;

    ConectorDocumentosLocales(
            IngestaRepositorio repo, ExtractorPdf extractorPdf,
            @Value("${kb.ingesta.corpus-dir}") String corpusDir) {
        this.repo = repo;
        this.extractorPdf = extractorPdf;
        this.raiz = Path.of(corpusDir);
    }

    record Resumen(
            int archivosVistos, int documentosActualizados, int documentosSinCambios,
            int documentosEliminados, int chunksCreados) {
    }

    /** Para el botón de ayuda de F9: una sola fuente de verdad para qué formatos se aceptan. */
    static Set<String> extensionesAceptadas() {
        return EXTENSIONES;
    }

    Resumen ingerir() {
        if (!Files.isDirectory(raiz)) {
            log.warn("El corpus {} no existe o no es un directorio; nada que ingerir", raiz);
            return new Resumen(0, 0, 0, 0, 0);
        }

        long sourceId = repo.obtenerOCrearFuente(KIND, NOMBRE_FUENTE);
        List<String> vistos = new ArrayList<>();
        int actualizados = 0;
        int sinCambios = 0;
        int chunksCreados = 0;

        List<Path> archivos = listarArchivosElegibles();
        for (Path archivo : archivos) {
            String externalId = rutaRelativaPosix(archivo);
            vistos.add(externalId);

            byte[] bytes = leer(archivo);
            String hash = sha256Hex(bytes);

            Optional<IngestaRepositorio.DocumentoExistente> existente = repo.buscarDocumento(sourceId, externalId);
            if (existente.isPresent() && existente.get().contentHash().equals(hash)) {
                sinCambios++;
                continue;
            }

            boolean esPdf = extension(archivo).filter("pdf"::equals).isPresent();
            String texto = esPdf ? extraerPdf(archivo) : new String(bytes, StandardCharsets.UTF_8);

            long documentoId = repo.upsertDocumento(
                    sourceId, externalId, "file:///corpus/" + externalId,
                    archivo.getFileName().toString(), texto, hash, ProyectoId.POR_DEFECTO.valor());

            int ord = 0;
            for (ChunkATexto chunk : trocear(texto, esPdf)) {
                String distilled = Json.escribir(Map.of("summary", chunk.resumen()));
                long chunkId = repo.insertarChunk(
                        documentoId, sourceId, ProyectoId.POR_DEFECTO.valor(), ord++,
                        "doc_section", chunk.cuerpo(), distilled);
                repo.encolarEmbeberChunk(chunkId);
                chunksCreados++;
            }
            actualizados++;
        }

        int eliminados = 0;
        for (long huerfano : repo.documentosHuerfanos(sourceId, vistos)) {
            repo.eliminarDocumento(huerfano);
            eliminados++;
        }

        log.info("Ingesta de documentos locales: {} vistos, {} actualizados, {} sin cambios, "
                        + "{} eliminados, {} chunks nuevos",
                archivos.size(), actualizados, sinCambios, eliminados, chunksCreados);

        // Este conector no tiene un token incremental que guardar en sync_state (a
        // diferencia de repos/Teams/Azure DevOps), pero SI debe marcar last_synced_at:
        // sin esto, la consola de administracion de F9 mostraria "nunca" aunque el
        // relevo automatico de F8 corra bien hace rato.
        repo.actualizarSyncState(sourceId, "{}");

        return new Resumen(archivos.size(), actualizados, sinCambios, eliminados, chunksCreados);
    }

    private List<Path> listarArchivosElegibles() {
        try (var stream = Files.walk(raiz)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> extension(p).map(EXTENSIONES::contains).orElse(false))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Fallo escaneando " + raiz, e);
        }
    }

    private record ChunkATexto(String cuerpo, String resumen) {
    }

    private static List<ChunkATexto> trocear(String texto, boolean esPdf) {
        if (esPdf) {
            return ChunkerVentanas.trocear(texto, MAX_CARACTERES_VENTANA_PDF).stream()
                    .map(cuerpo -> new ChunkATexto(cuerpo, resumenDe(List.of(), cuerpo)))
                    .toList();
        }
        return ChunkerEncabezados.trocear(texto).stream()
                .map(s -> new ChunkATexto(s.cuerpo(), resumenDe(s.rutaEncabezados(), s.cuerpo())))
                .toList();
    }

    /**
     * "Destilación" heurística para contenido ya estructurado: ruta de
     * encabezados más una síntesis del cuerpo, sin llamar al LLM. Ver
     * {@code package-info.java} de este módulo para por qué esto es
     * legítimo aquí y no lo sería para un hilo de Teams.
     */
    private static String resumenDe(List<String> ruta, String cuerpo) {
        String vista = cuerpo.replaceAll("\\s+", " ").strip();
        if (vista.length() > LONGITUD_RESUMEN) {
            vista = vista.substring(0, LONGITUD_RESUMEN) + "…";
        }
        return ruta.isEmpty() ? vista : String.join(" › ", ruta) + ": " + vista;
    }

    private String extraerPdf(Path archivo) {
        try {
            return extractorPdf.extraerTexto(archivo);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo extraer texto de " + archivo, e);
        }
    }

    private String rutaRelativaPosix(Path archivo) {
        return raiz.relativize(archivo).toString().replace('\\', '/');
    }

    private static byte[] leer(Path archivo) {
        try {
            return Files.readAllBytes(archivo);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + archivo, e);
        }
    }

    private static Optional<String> extension(Path archivo) {
        String nombre = archivo.getFileName().toString();
        int i = nombre.lastIndexOf('.');
        return i < 0 ? Optional.empty() : Optional.of(nombre.substring(i + 1).toLowerCase(Locale.ROOT));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 es obligatorio en toda JVM estandar; esto no deberia pasar nunca.
            throw new IllegalStateException(e);
        }
    }
}
