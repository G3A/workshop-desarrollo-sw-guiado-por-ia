package co.g3a.baseconocimiento.orquestacion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;

/**
 * Búsqueda literal sobre el código fuente en disco, con {@code ripgrep} —
 * el {@code search_code} del artículo. Deliberadamente NO consulta
 * {@code chunks}: es una herramienta que mira el árbol de archivos tal como
 * está ahora mismo, no un índice que puede haber quedado desactualizado.
 *
 * <p>{@code ripgrep} viaja en la imagen del contenedor desde F0 justamente
 * para esto (ver {@code Dockerfile}). En una máquina de desarrollo sin
 * {@code rg} en el {@code PATH}, o sin {@code kb.orquestacion.codigo-dir}
 * configurado, esta herramienta devuelve una lista vacía en vez de fallar.
 */
@Component
class HerramientaSearchCode implements Herramienta {

    private static final Logger log = LoggerFactory.getLogger(HerramientaSearchCode.class);

    /** {@code archivo:linea:contenido}; no greedy en el archivo para tolerar ':' en rutas de Windows. */
    private static final Pattern LINEA_RIPGREP = Pattern.compile("^(.*?):(\\d+):(.*)$");

    private final Path raiz;
    private final int maxResultados;

    HerramientaSearchCode(
            @Value("${kb.orquestacion.codigo-dir}") String codigoDir,
            @Value("${kb.orquestacion.herramientas.search-code.max-resultados:15}") int maxResultados) {
        this.raiz = Path.of(codigoDir);
        this.maxResultados = maxResultados;
    }

    @Override
    public String nombre() {
        return "search_code";
    }

    @Override
    public String descripcion() {
        return "Busca coincidencias literales en el codigo fuente con ripgrep. Util SOLO para "
                + "preguntas sobre como esta implementado algo puntual (un nombre de funcion, "
                + "una constante, un mensaje de error). NO para preguntas de requisitos de "
                + "hardware, instalacion, despliegue o configuracion -- esas se responden con "
                + "la documentacion (search_docs/search_unified), no con el codigo.";
    }

    @Override
    public List<Fragmento> ejecutar(String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
        if (!Files.isDirectory(raiz)) {
            log.debug("kb.orquestacion.codigo-dir ({}) no existe; search_code no tiene nada que buscar", raiz);
            return List.of();
        }

        try {
            Process proceso = new ProcessBuilder(
                    "rg", "--line-number", "--with-filename", "--no-heading", "--fixed-strings",
                    "--max-count", String.valueOf(maxResultados), "--", consulta, raiz.toString())
                    .redirectErrorStream(false)
                    .start();

            List<Fragmento> resultados = leerCoincidencias(proceso);
            boolean termino = proceso.waitFor(10, TimeUnit.SECONDS);
            if (!termino) {
                proceso.destroyForcibly();
            }
            return resultados;
        } catch (IOException e) {
            // "rg" no esta en el PATH: no es un error del pipeline, es un
            // entorno sin la herramienta instalada (p. ej. desarrollo fuera
            // del contenedor).
            log.debug("No se pudo invocar ripgrep: {}", e.toString());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private List<Fragmento> leerCoincidencias(Process proceso) throws IOException {
        List<Fragmento> resultados = new ArrayList<>();
        try (var lector = new BufferedReader(
                new InputStreamReader(proceso.getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = lector.readLine()) != null && resultados.size() < maxResultados) {
                var m = LINEA_RIPGREP.matcher(linea);
                if (!m.matches()) {
                    continue;
                }
                String archivo = m.group(1);
                int numeroLinea = Integer.parseInt(m.group(2));
                String contenido = m.group(3);
                resultados.add(aFragmento(archivo, numeroLinea, contenido));
            }
        }
        return resultados;
    }

    private Fragmento aFragmento(String archivo, int numeroLinea, String contenido) {
        String rutaRelativa = raiz.relativize(Path.of(archivo)).toString().replace('\\', '/');
        long idSintetico = (((long) rutaRelativa.hashCode()) << 32) | (numeroLinea & 0xffffffffL);
        return new Fragmento(
                idSintetico, 0L, "file:///" + rutaRelativa + "#L" + numeroLinea, rutaRelativa,
                contenido.strip(), "code_block", numeroLinea, Instant.now(), Map.of(), 0.0, null);
    }
}
