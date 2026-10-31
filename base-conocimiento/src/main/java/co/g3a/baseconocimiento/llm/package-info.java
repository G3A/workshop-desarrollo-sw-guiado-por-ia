/**
 * Acceso al modelo de generacion: OpenAI contra la instancia del LLM de {@code llama-server}, que
 * corre en el host (ADR-0017), para todos los componentes: planificador, reformulador, verificador
 * de grounding, sintetizador, redactor de acciones y destilador de Teams.
 *
 * <p>Spring AI se usa aqui y solo aqui, como cliente: chat, streaming y salida estructurada. Sus
 * abstracciones de RAG y su {@code VectorStore} quedan fuera a proposito — un {@code VectorStore}
 * no sabe expresar cuatro senales independientes fusionadas por RRF, que es justamente el aporte
 * del diseno.
 */
@org.springframework.modulith.ApplicationModule(displayName = "LLM")
package co.g3a.baseconocimiento.llm;
