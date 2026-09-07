/**
 * Acceso al modelo de generacion: OpenAI contra llama-server (Bonsai 8B, ver ADR-0009) para el
 * planificador, el verificador de grounding y el sintetizador; Ollama para el destilador de Teams
 * (F6), que se quedo ahi.
 *
 * <p>Spring AI se usa aqui y solo aqui, como cliente: chat, streaming y salida estructurada. Sus
 * abstracciones de RAG y su {@code VectorStore} quedan fuera a proposito — un {@code VectorStore}
 * no sabe expresar cuatro senales independientes fusionadas por RRF, que es justamente el aporte
 * del diseno.
 */
@org.springframework.modulith.ApplicationModule(displayName = "LLM")
package co.g3a.baseconocimiento.llm;
