package co.g3a.baseconocimiento.modelos;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code bge-reranker-v2-m3} sobre ONNX Runtime, en proceso.
 *
 * <p>Modelo y tokenizador se cargan de forma perezosa en el primer {@link #puntuar(String,
 * String)}, no al arrancar la aplicacion: cargar ~550 MB antes de que exista una sola consulta
 * haria que un `docker compose up` sin modelos descargados nunca llegara a estar sano. {@code make
 * pull-models} es quien los deja en {@code kb.reranker.ruta}.
 *
 * <p>El formato del par consulta/pasaje y el post-procesamiento (sigmoid sobre el logit crudo)
 * siguen el uso documentado de BAAI/bge-reranker-v2-m3: un solo llamado al tokenizador con
 * (consulta, pasaje) como par, y {@code sigmoid(logits[0])} como puntaje de relevancia en [0,1].
 * Aqui se multiplica por 10 para la escala 0-10 que el resto del pipeline espera.
 */
@Component
class RerankerOnnx implements Reranker, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RerankerOnnx.class);
  private static final int LONGITUD_MAXIMA = 512;

  private final Path directorioModelo;
  private final int hilos;

  private volatile HuggingFaceTokenizer tokenizador;
  private volatile OrtSession sesion;
  private volatile OrtEnvironment entorno;

  RerankerOnnx(@Value("${kb.reranker.ruta}") String ruta) {
    this.directorioModelo = Path.of(ruta);
    // Deja nucleos libres para la JVM y para Ollama, que suele correr en el
    // mismo host durante el desarrollo local.
    this.hilos = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
  }

  @Override
  public double puntuar(String consulta, String pasaje) {
    asegurarCargado();

    Encoding codificado = tokenizador.encode(consulta, pasaje);
    long[] ids = codificado.getIds();
    long[] mascara = codificado.getAttentionMask();
    long[] tipos = codificado.getTypeIds();
    long[] forma = {1, ids.length};

    Map<String, OnnxTensor> entradas = new LinkedHashMap<>();
    try {
      var nombresEsperados = sesion.getInputNames();
      entradas.put("input_ids", OnnxTensor.createTensor(entorno, LongBuffer.wrap(ids), forma));
      entradas.put(
          "attention_mask", OnnxTensor.createTensor(entorno, LongBuffer.wrap(mascara), forma));
      // No todos los exports de XLM-RoBERTa declaran token_type_ids como
      // entrada (type_vocab_size=1 en este modelo); se agrega solo si el
      // grafo ONNX en verdad lo pide, para no fallar en el export que no lo trae.
      if (nombresEsperados.contains("token_type_ids")) {
        entradas.put(
            "token_type_ids", OnnxTensor.createTensor(entorno, LongBuffer.wrap(tipos), forma));
      }

      try (OrtSession.Result resultado = sesion.run(entradas)) {
        double logit = primerValorEscalar(resultado.get(0).getValue());
        double sigmoide = 1.0 / (1.0 + Math.exp(-logit));
        return sigmoide * 10.0;
      }
    } catch (OrtException e) {
      throw new IllegalStateException("Fallo al ejecutar el reranker ONNX", e);
    } finally {
      entradas.values().forEach(OnnxTensor::close);
    }
  }

  /** Extrae el unico logit de un tensor de salida sin asumir su forma exacta. */
  private static double primerValorEscalar(Object valor) {
    return switch (valor) {
      case float[][] matriz -> matriz[0][0];
      case float[] vector -> vector[0];
      default ->
          throw new IllegalStateException(
              "Forma de salida inesperada del reranker: " + valor.getClass());
    };
  }

  private void asegurarCargado() {
    if (sesion != null) {
      return;
    }
    synchronized (this) {
      if (sesion != null) {
        return;
      }
      cargar();
    }
  }

  private void cargar() {
    Path modelo = directorioModelo.resolve("model.onnx");
    Path tokenizerJson = directorioModelo.resolve("tokenizer.json");

    if (!Files.exists(modelo) || !Files.exists(tokenizerJson)) {
      throw new IllegalStateException(
          "Faltan los archivos del reranker en %s. Corre: make pull-models"
              .formatted(directorioModelo));
    }

    try {
      log.info("Cargando reranker desde {} con {} hilos", directorioModelo, hilos);

      this.tokenizador =
          HuggingFaceTokenizer.builder()
              .optTokenizerPath(directorioModelo)
              .optTruncation(true)
              .optMaxLength(LONGITUD_MAXIMA)
              .build();

      this.entorno = OrtEnvironment.getEnvironment();
      try (var opciones = new OrtSession.SessionOptions()) {
        opciones.setIntraOpNumThreads(hilos);
        opciones.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.sesion = entorno.createSession(modelo.toString(), opciones);
      }

      log.info("Reranker listo. Entradas del grafo ONNX: {}", sesion.getInputNames());
    } catch (IOException e) {
      throw new UncheckedIOException("No se pudo leer el tokenizador del reranker", e);
    } catch (OrtException e) {
      throw new IllegalStateException("No se pudo cargar la sesion ONNX del reranker", e);
    }
  }

  @Override
  public void close() {
    // No declara "throws Exception": AutoCloseable.close() puede sobreescribirse con una
    // firma mas angosta, y -Xlint:try marca "Exception" crudo como riesgo de tragarse un
    // InterruptedException. tokenizador.close() (DJL) declara Exception generico; se
    // relanza envuelto en vez de propagar el checked exception crudo.
    try {
      if (sesion != null) {
        sesion.close();
      }
      if (tokenizador != null) {
        tokenizador.close();
      }
    } catch (OrtException e) {
      throw new IllegalStateException("No se pudo cerrar la sesion ONNX del reranker", e);
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo cerrar el tokenizador del reranker", e);
    }
  }
}
