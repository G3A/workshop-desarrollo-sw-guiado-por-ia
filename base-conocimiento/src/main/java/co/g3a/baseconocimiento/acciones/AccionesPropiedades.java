package co.g3a.baseconocimiento.acciones;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code kb.acciones.*}: los topes del modulo. Los defaults viven en {@code application.yml} y
 * estan pensados para el perfil mas chico ({@code num_ctx} 4096 con Bonsai).
 *
 * @param maxDocumentos cuantos documentos admite una accion; mas documentos no rompen el servidor,
 *     reparten el presupuesto entre mas (y los que no caben se leen por pasadas)
 * @param maxCaracteresContexto presupuesto TOTAL de texto que entra al LLM en la llamada final de
 *     resumir, sintetizar, preguntas e ideas; la traduccion no lo usa (va por bloques)
 * @param maxCaracteresTexto largo maximo de un texto del chat a traducir
 * @param maxConcurrentes cupo propio del modulo, sin compartir con el del RAG (compartirlo seria
 *     depender de {@code orquestacion}); sumados pueden superar {@code OLLAMA_NUM_PARALLEL} y en
 *     ese caso Ollama encola: la accion tarda mas, no falla
 * @param maxCaracteresLectura cuanto texto entra en UNA pasada de lectura de un documento largo
 *     (sub-issue #60): la llamada devuelve notas cortas, asi que puede ser mayor que el presupuesto
 *     de contexto; cuanto mas grande, menos pasadas
 */
@ConfigurationProperties(prefix = "kb.acciones")
record AccionesPropiedades(
    int maxDocumentos,
    int maxCaracteresContexto,
    int maxCaracteresTexto,
    int maxConcurrentes,
    int maxCaracteresLectura) {}
