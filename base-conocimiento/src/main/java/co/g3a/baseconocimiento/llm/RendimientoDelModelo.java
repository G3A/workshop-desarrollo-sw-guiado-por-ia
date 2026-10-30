package co.g3a.baseconocimiento.llm;

/**
 * Lo que rinde el modelo de generacion en este equipo, aprendido de las llamadas reales (issue
 * #57). Sirve para dos cosas que antes se suponian: cuanto texto entra en una llamada sin desbordar
 * el contexto, y cuanto va a tardar una accion antes de empezarla.
 *
 * <p>Se supone una llamada que cuesta {@code tokensEntrada × a + tokensSalida × b} segundos. Con
 * Granite 4.1 3B en la T600 la entrada domina: lee ~18 tokens/s y escribe ~7, y una pasada de
 * lectura son ~3700 tokens de entrada contra 30–70 de salida.
 */
public interface RendimientoDelModelo {

  /** Caracteres por token de entrada: ~2,7 en ingles tecnico con codigo, ~3,6 en prosa. */
  double caracteresPorToken();

  /** Segundos estimados de una llamada con {@code caracteresEntrada} y {@code tokensSalida}. */
  double segundosEstimados(int caracteresEntrada, int tokensSalida);

  /** Tokens de contexto del modelo ({@code num_ctx} de Ollama): entrada y salida juntas. */
  int contextoTokens();
}
