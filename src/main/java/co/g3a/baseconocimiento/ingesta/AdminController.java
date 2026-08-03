package co.g3a.baseconocimiento.ingesta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final Path corpusDir;
    private final Path reposDir;
    private final long relevoIntervaloMs;
    private final boolean relevoHabilitado;
    private final boolean cargaHabilitada;

    AdminController(
            IngestaRepositorio repo, RelevadorDeFuentes relevador,
            @Value("${kb.ingesta.corpus-dir}") String corpusDir,
            @Value("${kb.ingesta.repos-dir}") String reposDir,
            @Value("${kb.ingesta.relevo.intervalo-ms:900000}") long relevoIntervaloMs,
            @Value("${kb.ingesta.relevo.habilitado:true}") boolean relevoHabilitado,
            @Value("${kb.ingesta.carga-habilitada:false}") boolean cargaHabilitada) {
        this.repo = repo;
        this.relevador = relevador;
        this.corpusDir = Path.of(corpusDir);
        this.reposDir = Path.of(reposDir);
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
     * Para el selector de proyecto de F10 (chat) y de la propia consola.
     * Excluido de {@code ApiTokenFilter} igual que {@code /api/admin/ayuda}:
     * la página de chat lo necesita sin sesión ni token.
     */
    @GetMapping("/api/admin/proyectos")
    List<String> proyectos() {
        return repo.listarProyectos();
    }

    record Ayuda(
            String corpusDir, String reposDir, List<String> extensionesAceptadas,
            long relevoIntervaloMs, boolean relevoHabilitado, boolean cargaHabilitada) {
    }

    /**
     * Lo que el botón {@code ?} de la UI muestra: rutas y valores reales que
     * el servidor está usando, no una convención escrita a mano en el HTML —
     * ver la discusión de {@code KB_CORPUS_DIR} vs {@code KB_CORPUS_RUTA} en
     * {@code docs/plans/plan-base-conocimiento.md}.
     */
    @GetMapping("/api/admin/ayuda")
    Ayuda ayuda() {
        return new Ayuda(
                corpusDir.toString(), reposDir.toString(),
                ConectorDocumentosLocales.extensionesAceptadas().stream().sorted().toList(),
                relevoIntervaloMs, relevoHabilitado, cargaHabilitada);
    }

    /**
     * Carga desde el navegador, apagada por defecto. Habilitarla
     * ({@code KB_INGESTA_CARGA_HABILITADA=true}) exige además quitar el
     * {@code :ro} del bind mount del corpus en {@code compose.yml}: sin eso,
     * el contenedor no puede escribir ahí y esta llamada falla con un error
     * de E/S explícito, no en silencio.
     */
    @PostMapping("/api/admin/corpus/archivos")
    ResponseEntity<String> subirArchivo(@RequestParam("archivo") MultipartFile archivo) {
        if (!cargaHabilitada) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Carga deshabilitada (kb.ingesta.carga-habilitada=false)");
        }
        Optional<String> nombreValido = validarNombre(archivo.getOriginalFilename());
        if (nombreValido.isEmpty()) {
            return ResponseEntity.badRequest().body("Nombre de archivo invalido o extension no aceptada");
        }
        Path destino = corpusDir.resolve(nombreValido.get()).normalize();
        if (!destino.startsWith(corpusDir.normalize())) {
            return ResponseEntity.badRequest().body("Ruta de archivo invalida");
        }
        try {
            Files.createDirectories(corpusDir);
            archivo.transferTo(destino);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir " + destino, e);
        }
        return ResponseEntity.ok("Archivo guardado: " + nombreValido.get());
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
