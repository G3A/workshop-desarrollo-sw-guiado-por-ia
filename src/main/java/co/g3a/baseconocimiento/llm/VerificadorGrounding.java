package co.g3a.baseconocimiento.llm;

/**
 * ADR-0008: segundo filtro antes de sintetizar, solo para la zona ambigua de
 * {@code UmbralRelevancia} — donde el score del cross-encoder no alcanza a
 * decidir por sí solo si el contexto de verdad responde la pregunta o solo
 * comparte vocabulario superficial (ver el caso "como usar java 25" en el
 * ADR: puntuó más alto que una pregunta genuinamente relevante).
 *
 * <p>A diferencia de {@link Sintetizador}, no redacta nada — emite un
 * veredicto binario sobre una pregunta acotada y puntual, que un modelo
 * chico puede seguir de forma más confiable que la instrucción abierta
 * "responde, pero niégate si no alcanza" del prompt de síntesis.
 */
public interface VerificadorGrounding {

    record Veredicto(boolean respondeLaPregunta) {
    }

    Veredicto verificar(String pregunta, String contexto);
}
