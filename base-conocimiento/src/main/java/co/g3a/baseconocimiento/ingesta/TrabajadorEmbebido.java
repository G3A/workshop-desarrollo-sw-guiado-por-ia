package co.g3a.baseconocimiento.ingesta;

import co.g3a.baseconocimiento.modelos.Embeddings;
import com.pgvector.PGvector;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * El trabajo real de un item de la cola: toma un trabajo {@code embeber_chunk} y llama al modelo de
 * embeddings.
 *
 * <p><b>Ninguna conexión a la base queda tomada mientras el modelo calcula.</b> Antes todo {@code
 * procesarUno()} era una sola transacción, y la conexión seguía tomada durante la llamada a Ollama,
 * que tarda segundos. {@link TrabajadorEmbebidoProgramador} lanza 25 a la vez contra un pool de 10:
 * con un PDF grande, la ingesta se quedaba sin conexiones y moría a los 30 s con "Failed to obtain
 * JDBC Connection" (ADR-0014). Ahora son tres pasos: tomar el trabajo y guardar el resultado van en
 * transacciones cortas del repositorio, y la llamada al modelo queda afuera.
 *
 * <p>Lo que se pierde es el rollback automático si el proceso muere a mitad del trabajo: eso lo
 * cubre {@link #PLAZO_SEGUNDOS}, el tiempo tras el cual un trabajo en {@code running} se da por
 * abandonado y se vuelve a tomar.
 */
@Component
class TrabajadorEmbebido {

  private static final Logger log = LoggerFactory.getLogger(TrabajadorEmbebido.class);
  private static final int MAX_INTENTOS = 5;

  /**
   * Embeber un chunk tarda segundos, aun con 25 en cola contra un Ollama en CPU (medido: ~1 por
   * segundo). Diez minutos es holgura de sobra antes de dar un trabajo por abandonado. Si se
   * retomara uno que en realidad seguía vivo, el costo es embeber el mismo chunk dos veces: el
   * resultado es idéntico.
   */
  static final long PLAZO_SEGUNDOS = 600;

  private final IngestaRepositorio repo;
  private final Embeddings embeddings;

  TrabajadorEmbebido(IngestaRepositorio repo, Embeddings embeddings) {
    this.repo = repo;
    this.embeddings = embeddings;
  }

  boolean procesarUno() {
    var trabajoOpt = repo.tomarSiguienteTrabajo(PLAZO_SEGUNDOS);
    if (trabajoOpt.isEmpty()) {
      return false;
    }
    var trabajo = trabajoOpt.get();
    try {
      switch (trabajo.kind()) {
        case "embeber_chunk" -> embeberChunk(trabajo);
        default -> {
          log.warn("Tipo de trabajo desconocido, se descarta: {}", trabajo.kind());
          repo.marcarHecho(trabajo.id());
        }
      }
    } catch (Exception e) {
      log.warn(
          "Trabajo {} ({}) fallo en el intento {}: {}",
          trabajo.id(),
          trabajo.kind(),
          trabajo.attempts() + 1,
          e.toString());
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

    // Sin transacción abierta: esta llamada tarda segundos y no debe retener una conexión.
    float[] vector = embeddings.embeber(textoParaEmbeber);
    repo.completarEmbedding(trabajo.id(), chunkId, new PGvector(vector));
  }

  private static String trazaCorta(Exception e) {
    var sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    String traza = sw.toString();
    return traza.length() > 2_000 ? traza.substring(0, 2_000) : traza;
  }
}
