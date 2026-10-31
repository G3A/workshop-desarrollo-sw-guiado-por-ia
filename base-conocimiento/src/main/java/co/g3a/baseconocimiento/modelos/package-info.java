/**
 * Acceso a los dos modelos que no generan texto: embeddings y el cross-encoder de reranking.
 *
 * <p>Los embeddings ({@code bge-m3}) los sirve {@code llama-server} en el host (ADR-0017) y se
 * exponen aquí detrás de una interfaz propia para no filtrar el tipo de Spring AI al resto del
 * núcleo. El reranker corre en proceso sobre ONNX Runtime.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Modelos")
package co.g3a.baseconocimiento.modelos;
