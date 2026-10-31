package co.g3a.baseconocimiento.modelos;

/**
 * Cross-encoder: la defensa contra falsos positivos que la similitud vectorial sola no da. Corre en
 * proceso, sobre ONNX Runtime.
 */
public interface Reranker {

  /**
   * Puntúa qué tan relevante es un pasaje para una consulta, en escala 0-10.
   *
   * <p>Encima de esta interfaz no hay nada que dependa de ONNX Runtime ni de cómo se tokeniza el
   * par consulta/pasaje.
   */
  double puntuar(String consulta, String pasaje);
}
