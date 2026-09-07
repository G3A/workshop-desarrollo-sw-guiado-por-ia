package co.g3a.baseconocimiento.acciones;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code kb.acciones.*}: los topes del modulo. Los defaults viven en {@code application.yml} y
 * estan pensados para el perfil mas chico ({@code num_ctx} 4096 con Bonsai).
 *
 * @param maxDocumentos cuantos documentos admite una accion; mas documentos no rompen el servidor,
 *     degradan la cobertura de cada uno (el presupuesto se reparte entre ellos)
 * @param maxCaracteresContexto presupuesto TOTAL de texto de secciones que entra al LLM para
 *     resumir, sintetizar, preguntas e ideas; la traduccion no lo usa (va por bloques)
 * @param maxCaracteresTexto largo maximo de un texto del chat a traducir
 * @param maxConcurrentes cupo propio del modulo, sin compartir con el del RAG (compartirlo seria
 *     depender de {@code orquestacion}); sumados pueden superar {@code OLLAMA_NUM_PARALLEL} y en
 *     ese caso Ollama encola: la accion tarda mas, no falla
 */
@ConfigurationProperties(prefix = "kb.acciones")
record AccionesPropiedades(
    int maxDocumentos, int maxCaracteresContexto, int maxCaracteresTexto, int maxConcurrentes) {}
