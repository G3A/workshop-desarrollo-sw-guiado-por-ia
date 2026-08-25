package co.g3a.baseconocimiento.ingesta;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pgvector.PGvector;

import co.g3a.baseconocimiento.modelos.Embeddings;

/**
 * El trabajo real de un item de la cola: toma un trabajo {@code embeber_chunk}
 * y llama al modelo de embeddings.
 *
 * <p>Está separado de {@link TrabajadorEmbebidoProgramador} a propósito:
 * {@code @Transactional} se aplica vía proxy de Spring, y un método que se
 * llama a sí mismo dentro de la misma clase ({@code this.procesarUno()}) nunca
 * pasa por ese proxy. Con dos clases, la llamada sí cruza el proxy y la
 * transacción existe de verdad — de lo contrario "tomar trabajo" y "marcar
 * hecho" quedarían como sentencias sueltas en autocommit, no como una unidad.
 */
@Component
class TrabajadorEmbebido {

    private static final Logger log = LoggerFactory.getLogger(TrabajadorEmbebido.class);
    private static final int MAX_INTENTOS = 5;

    private final IngestaRepositorio repo;
    private final Embeddings embeddings;

    TrabajadorEmbebido(IngestaRepositorio repo, Embeddings embeddings) {
        this.repo = repo;
        this.embeddings = embeddings;
    }

    @Transactional
    boolean procesarUno() {
        var trabajoOpt = repo.tomarSiguienteTrabajo();
        if (trabajoOpt.isEmpty()) {
            return false;
        }
        var trabajo = trabajoOpt.get();
        try {
            switch (trabajo.kind()) {
                case "embeber_chunk" -> embeberChunk(trabajo);
                default -> log.warn("Tipo de trabajo desconocido, se descarta: {}", trabajo.kind());
            }
            repo.marcarHecho(trabajo.id());
        } catch (Exception e) {
            log.warn("Trabajo {} ({}) fallo en el intento {}: {}",
                    trabajo.id(), trabajo.kind(), trabajo.attempts() + 1, e.toString());
            repo.marcarFallo(trabajo.id(), trabajo.attempts(), MAX_INTENTOS, trazaCorta(e));
        }
        return true;
    }

    private void embeberChunk(IngestaRepositorio.Trabajo trabajo) {
        long chunkId = Json.leer(trabajo.payload()).get("chunk_id").asLong();
        var chunk = repo.obtenerChunk(chunkId);

        var distilled = Json.leer(chunk.distilledJson());
        String pregunta = Json.textoDe(distilled, "searchable_question");
        String resumen = Json.textoDe(distilled, "summary");
        String textoParaEmbeber = (pregunta + "\n" + resumen).strip();

        // Red de seguridad: si por lo que sea no hay destilado (no deberia
        // pasar para local_docs), se usa el texto crudo antes que fallar.
        if (textoParaEmbeber.isEmpty()) {
            textoParaEmbeber = chunk.texto();
        }

        float[] vector = embeddings.embeber(textoParaEmbeber);
        repo.actualizarEmbedding(chunkId, new PGvector(vector));
    }

    private static String trazaCorta(Exception e) {
        var sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String traza = sw.toString();
        return traza.length() > 2_000 ? traza.substring(0, 2_000) : traza;
    }
}
