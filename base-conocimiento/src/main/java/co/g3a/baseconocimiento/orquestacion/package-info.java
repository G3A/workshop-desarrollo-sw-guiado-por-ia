/**
 * Orquestacion del pipeline de siete etapas: planner, executor sobre las seis
 * herramientas, fusion RRF, dedup, reranking, expansion de contexto y sintesis.
 *
 * <p>Expone {@link co.g3a.baseconocimiento.orquestacion.Consultar}, la unica puerta
 * que los adaptadores tienen permitido cruzar. Todo lo demas es {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Orquestacion")
package co.g3a.baseconocimiento.orquestacion;
