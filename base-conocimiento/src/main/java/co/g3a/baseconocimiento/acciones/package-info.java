/**
 * Acciones sobre documentos elegidos a mano: resumir, sintetizar, lluvia de preguntas, lluvia de
 * ideas y traducir (documentos completos o un texto del chat).
 *
 * <p>Independiente del RAG a proposito (issue #38, ADR-0013): no hay pregunta que planificar, nada
 * que recuperar ni relevancia que juzgar — la persona ya dijo que documentos. Con {@code
 * orquestacion} comparte solo dos cosas, y ninguna es codigo: el vault ya indexado (SQL propio
 * sobre {@code documents}/{@code chunks}) y el cliente del LLM ({@code llm}). Una regla de ArchUnit
 * lo hace cumplir en cada build.
 *
 * <p>Expone {@link co.g3a.baseconocimiento.acciones.Acciones}, la segunda puerta que los
 * adaptadores pueden cruzar (la primera es {@code orquestacion.Consultar}). Todo lo demas es
 * interno.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Acciones")
package co.g3a.baseconocimiento.acciones;
