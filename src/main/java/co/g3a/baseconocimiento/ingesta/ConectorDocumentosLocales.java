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
import org.springframework.web.client.HttpClientErrorException;

import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;

/**
 * Ingiere {@code <vault>/documentos}: Markdown y texto por encabezados; PDF,
 * DOCX y PPTX por el mismo camino, convertidos primero a Markdown por Docling
 * (ADR-0010). Es el conector que garantiza que el producto sea demostrable
 * sin ninguna credencial de terceros.
 */
@Component
class ConectorDocumentosLocales {

    private static final Logger log = LoggerFactory.getLogger(ConectorDocumentosLocales.class);
    private static final String KIND = "local_docs";
    // OJO: a proposito NO es "documentos" pese a que la carpeta se llame asi
    // desde el vault unificado (ADR-0011). Es solo el identificador logico en
    // `sources` (kind, name) -- cambiarlo crearia una fuente nueva sin
    // historial, y el emparejamiento de "este archivo ya lo tengo, no cambio"
    // se hace por (source_id, ruta), asi que TODO archivo parece nuevo de
    // vuelta: fuerza reingerir y reextraer con Docling cada PDF/DOCX/PPTX ya
    // ingerido, sin ningun beneficio real. Verificado en vivo con jls25.pdf
    // (900 paginas): renombrar la fuente disparo una reextraccion de ~15-25
    // min que era completamente evitable.
    private static final String NOMBRE_FUENTE = "corpus";
    private static final Set<String> EXTENSIONES_DOCLING = Set.of("pdf", "docx", "pptx");
    private static final Set<String> EXTENSIONES = Set.of("md", "markdown", "txt", "pdf", "docx", "pptx");
    private static final int LONGITUD_RESUMEN = 280;

    private final IngestaRepositorio repo;
    private final ExtractorDocling extractorDocling;
    private final Path raiz;

    ConectorDocumentosLocales(
            IngestaRepositorio repo, ExtractorDocling extractorDocling,
            @Value("${kb.ingesta.vault-dir}") String vaultDir) {
        this.repo = repo;
        this.extractorDocling = extractorDocling;
        this.raiz = Path.of(vaultDir).resolve("documentos");
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
            log.warn("{} no existe o no es un directorio; nada que ingerir", raiz);
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

            String proyecto = proyectoDe(externalId);
            byte[] bytes = leer(archivo);
            String hash = sha256Hex(bytes);
            repo.marcarArchivoDetectado(sourceId, externalId, bytes.length);

            Optional<IngestaRepositorio.DocumentoExistente> existente = repo.buscarDocumento(sourceId, externalId);
            if (existente.isPresent() && existente.get().contentHash().equals(hash)) {
                sinCambios++;
                continue;
            }

            try {
                boolean requiereDocling = extension(archivo).map(EXTENSIONES_DOCLING::contains).orElse(false);
                if (requiereDocling) {
                    repo.marcarArchivoExtrayendo(sourceId, externalId);
                }
                String texto = requiereDocling
                        ? extraerViaDocling(sourceId, externalId, archivo.getFileName().toString(), bytes)
                        : new String(bytes, StandardCharsets.UTF_8);

                long documentoId = repo.upsertDocumento(
                        sourceId, externalId, VaultUri.deDocumentoLocal(externalId),
                        archivo.getFileName().toString(), texto, hash, proyecto);
                repo.marcarArchivoProcesado(sourceId, externalId, documentoId);

                int ord = 0;
                for (ChunkATexto chunk : trocear(texto)) {
                    String distilled = Json.escribir(Map.of("summary", chunk.resumen()));
                    long chunkId = repo.insertarChunk(
                            documentoId, sourceId, proyecto, ord++,
                            "doc_section", chunk.cuerpo(), distilled);
                    repo.encolarEmbeberChunk(chunkId);
                    chunksCreados++;
                }
                actualizados++;
            } catch (RuntimeException e) {
                // Un archivo problematico (p. ej. un PDF de cientos de paginas que
                // docling-serve no llega a convertir a tiempo, ver ADR-0010) no debe
                // tumbar el resto del vault: se salta, sigue "visto" (no se borra
                // como huerfano) y se reintenta en el proximo relevo sin hash
                // actualizado, igual que el aislamiento de fallos del Executor.
                repo.marcarArchivoError(sourceId, externalId, e.getMessage());
                log.warn("No se pudo ingerir {}: {}", externalId, e.getMessage());
            }
        }

        int eliminados = 0;
        for (long huerfano : repo.documentosHuerfanos(sourceId, vistos)) {
            repo.eliminarDocumento(huerfano);
            eliminados++;
        }
        repo.eliminarArchivosVaultHuerfanos(sourceId, vistos);

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

    /**
     * Somete (o retoma) una tarea async de docling-serve para {@code externalId}
     * y espera su resultado (ADR-0010).
     *
     * <p>El candado en memoria de {@link RelevadorDeFuentes} ya impide que dos
     * corridas de {@code ingerir()} se pisen mientras el proceso sigue vivo,
     * pero se pierde si {@code kb-api} se reinicia a mitad de una conversión
     * de varios minutos. {@code docling_tareas_en_curso} sobrevive a ese
     * reinicio: si ya hay una tarea registrada para este archivo, se retoma
     * en vez de mandar una duplicada a docling-serve.
     */
    private String extraerViaDocling(long sourceId, String externalId, String nombreArchivo, byte[] bytes) {
        Optional<String> tareaPrevia = repo.buscarTareaDoclingEnCurso(sourceId, externalId);
        String taskId;
        if (tareaPrevia.isPresent()) {
            taskId = tareaPrevia.get();
            log.info("Retomando la tarea {} de docling-serve para {} (kb-api se reinició durante la conversión anterior)",
                    taskId, externalId);
        } else {
            taskId = extractorDocling.submitirTarea(nombreArchivo, bytes);
            repo.registrarTareaDoclingEnCurso(sourceId, externalId, taskId);
        }

        try {
            String texto = extractorDocling.esperarYExtraer(nombreArchivo, taskId);
            repo.borrarTareaDoclingEnCurso(sourceId, externalId);
            return texto;
        } catch (HttpClientErrorException.NotFound e) {
            if (tareaPrevia.isEmpty()) {
                // Una tarea recien creada no deberia dar 404 -- no ocultar un bug real detras
                // del camino de reintento pensado para tareas heredadas de un reinicio previo.
                throw e;
            }
            log.warn("La tarea heredada {} para {} ya no existe en docling-serve (¿también se reinició?); "
                    + "se reintenta con una tarea nueva", taskId, externalId);
            repo.borrarTareaDoclingEnCurso(sourceId, externalId);
            return extraerViaDocling(sourceId, externalId, nombreArchivo, bytes);
        } catch (RuntimeException e) {
            repo.borrarTareaDoclingEnCurso(sourceId, externalId);
            throw e;
        }
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

    private static List<ChunkATexto> trocear(String texto) {
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

    private String rutaRelativaPosix(Path archivo) {
        return raiz.relativize(archivo).toString().replace('\\', '/');
    }

    /**
     * El primer segmento de la ruta relativa ES el proyecto: un archivo suelto
     * en la raíz de {@code documentos/} cae en {@link ProyectoId#POR_DEFECTO},
     * uno bajo {@code documentos/<proyecto>/...} cae en {@code <proyecto>}.
     */
    private static String proyectoDe(String externalId) {
        int barra = externalId.indexOf('/');
        return barra < 0 ? ProyectoId.POR_DEFECTO.valor() : new ProyectoId(externalId.substring(0, barra)).valor();
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
