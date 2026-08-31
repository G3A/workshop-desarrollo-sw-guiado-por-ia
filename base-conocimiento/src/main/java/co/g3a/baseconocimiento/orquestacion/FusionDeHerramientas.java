package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Etapa 4: combina los fragmentos de todas las herramientas que corrió el {@link Executor} en una
 * sola lista para la síntesis. Dos herramientas distintas pueden traer el mismo chunk (p. ej.
 * {@code search_unified} y {@code search_docs} en la misma pregunta) — se deduplica por id,
 * quedándose con la instancia de mejor puntaje.
 *
 * <p>Pura y sin dependencias de Spring, igual que {@code RrfFusion} en {@code recuperacion}: así se
 * prueba por sus propios méritos sin levantar un contexto.
 */
final class FusionDeHerramientas {

  private FusionDeHerramientas() {}

  static List<Fragmento> combinar(
      List<List<Fragmento>> resultadosPorHerramienta, int maxFragmentos) {
    Map<Long, Fragmento> porId = new LinkedHashMap<>();
    for (List<Fragmento> fragmentos : resultadosPorHerramienta) {
      for (Fragmento f : fragmentos) {
        porId.merge(f.id(), f, FusionDeHerramientas::masFuerte);
      }
    }
    return porId.values().stream()
        .sorted((a, b) -> Double.compare(puntaje(b), puntaje(a)))
        .limit(maxFragmentos)
        .toList();
  }

  private static Fragmento masFuerte(Fragmento a, Fragmento b) {
    return puntaje(b) > puntaje(a) ? b : a;
  }

  /**
   * Si el reranker no llegó a puntuar el fragmento (herramientas fuera de recuperacion), usa RRF.
   */
  private static double puntaje(Fragmento f) {
    return f.rerank() != null ? f.rerank() : f.rrf();
  }
}
