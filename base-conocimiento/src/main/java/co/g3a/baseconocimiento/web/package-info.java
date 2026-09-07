/**
 * El adaptador web: una página HTML/JavaScript sin build, servida como estáticos, más REST y SSE.
 * Solo conoce {@link co.g3a.baseconocimiento.orquestacion.Consultar} y el vocabulario de {@link
 * co.g3a.baseconocimiento.compartido.Dominio} — una prueba de ArchUnit verifica que no llegue a
 * {@code recuperacion}, {@code ingesta}, {@code modelos} ni {@code llm}.
 *
 * <p>Muestra resultados de texto completo al instante (vía {@code Consultar.previsualizar})
 * mientras el pipeline completo de siete etapas corre detrás, y transmite la síntesis por SSE con
 * {@code Consultar.responderEnStreaming} — el "keyword search on landing" del artículo de Cerebras.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Web")
package co.g3a.baseconocimiento.web;
