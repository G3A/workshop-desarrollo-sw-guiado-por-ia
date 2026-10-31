package co.g3a.baseconocimiento.modelos;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * Adaptador sobre {@link EmbeddingModel} de Spring AI, servido por {@code llama-server} con {@code
 * bge-m3} por la API compatible con OpenAI (ADR-0017).
 *
 * <p>Es la única clase del núcleo que conoce el tipo de Spring AI para embeddings: el resto depende
 * solo de {@link Embeddings}.
 */
@Component
class EmbeddingsOpenAi implements Embeddings {

  private final EmbeddingModel modelo;

  EmbeddingsOpenAi(EmbeddingModel modelo) {
    this.modelo = modelo;
  }

  @Override
  public float[] embeber(String texto) {
    float[] vector = modelo.embed(texto);
    if (vector.length != DIMENSIONES) {
      // Si esto salta, lo mas probable es que la instancia de embeddings de llama-server
      // (scripts/llama-cpp.mjs) este sirviendo otro GGUF que bge-m3, o que
      // spring.ai.openai.embedding.base-url apunte a la instancia del LLM.
      throw new IllegalStateException(
          "El modelo de embeddings devolvio %d dimensiones, se esperaban %d"
              .formatted(vector.length, DIMENSIONES));
    }
    return vector;
  }
}
