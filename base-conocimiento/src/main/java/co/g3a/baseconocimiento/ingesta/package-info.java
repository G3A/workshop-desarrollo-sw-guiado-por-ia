/**
 * Ingesta: conectores, chunking, cola de trabajo y embebido de cada chunk nuevo o cambiado.
 *
 * <p>La destilación por LLM es propia de contenido conversacional ruidoso (hilos de Teams, ver F6)
 * — no de documentos ya estructurados. Para documentos y código, "destilar" es heurístico:
 * encabezados, rutas y una síntesis del cuerpo, sin llamar al modelo. El artículo de Cerebras hace
 * la misma distinción: destila Slack, pero solo trocea GitHub y Confluence.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Ingesta")
package co.g3a.baseconocimiento.ingesta;
