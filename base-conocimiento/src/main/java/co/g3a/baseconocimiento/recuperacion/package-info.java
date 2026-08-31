/**
 * Recuperación híbrida: las cuatro señales, su fusión por rango (RRF) y el cross-encoder que
 * reordena antes de que {@code orquestacion} sintetice.
 *
 * <p>Todo el SQL de las señales vive aquí, escrito a mano sobre {@code JdbcClient} —
 * deliberadamente, no sobre el {@code VectorStore} de Spring AI: esa abstracción no sabe expresar
 * cuatro señales independientes fusionadas por RRF. Adoptarla ahí sería perder justo lo que hace
 * valioso al diseño, a cambio de comodidad.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Recuperacion")
package co.g3a.baseconocimiento.recuperacion;
