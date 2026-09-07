package co.g3a.baseconocimiento.modelos;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * Adaptador sobre {@link EmbeddingModel} de Spring AI, servido por Ollama ({@code bge-m3}).
 *
 * <p>Es la única clase del núcleo que conoce el tipo de Spring AI para embeddings: el resto depende
 * solo de {@link Embeddings}.
 */
@Component
class EmbeddingsOllama implements Embeddings {

  private final EmbeddingModel modelo;

  EmbeddingsOllama(EmbeddingModel modelo) {
    this.modelo = modelo;
  }

  @Override
  public float[] embeber(String texto) {
    float[] vector = modelo.embed(texto);
    if (vector.length != DIMENSIONES) {
      // Si esto salta, lo mas probable es que kb.embeddings.modelo apunte a
      // otro modelo que bge-m3, o que la propiedad de Spring AI se haya
      // ignorado en silencio (ver comentario en application.yml sobre el
      // aplanamiento de propiedades en la 2.0).
      throw new IllegalStateException(
          "El modelo de embeddings devolvio %d dimensiones, se esperaban %d"
              .formatted(vector.length, DIMENSIONES));
    }
    return vector;
  }
}
