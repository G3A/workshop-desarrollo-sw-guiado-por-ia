/**
 * Acceso a los dos modelos que no vienen de Ollama: embeddings y el cross-encoder de reranking.
 *
 * <p>Los embeddings sí pasan por Ollama ({@code bge-m3}) y se exponen aquí detrás de una interfaz
 * propia para no filtrar el tipo de Spring AI al resto del núcleo. El reranker corre en proceso
 * sobre ONNX Runtime porque Ollama no sirve cross-encoders.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Modelos")
package co.g3a.baseconocimiento.modelos;
