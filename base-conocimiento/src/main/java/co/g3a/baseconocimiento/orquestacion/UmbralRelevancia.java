package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import java.util.List;
import java.util.Objects;

/**
 * ADR-0008: la puerta de relevancia antes de sintetizar, en la etapa 4 del pipeline (después de
 * {@link FusionDeHerramientas}, antes de expandir contexto). Reemplaza la confianza ciega en que el
 * LLM reconozca por su cuenta que el contexto no alcanza — probado con {@code gemma3:4b} y {@code
 * granite4.1:3b}, ninguno lo hizo de forma confiable cuando el contexto es tangencial en vez de
 * vacío (ver ADR-0008 para la traza completa) — por una decisión determinista sobre el score del
 * cross-encoder.
 *
 * <p>Solo mira fragmentos que de verdad vienen de una búsqueda por relevancia ({@code rerank !=
 * null}). Las herramientas de listado directo ({@code recent_commits}, {@code subsystem_index},
 * {@code who_knows}) no rankean por relevancia a la consulta — su propia elección por el planner ya
 * es la señal — así que su presencia entre los fragmentos deja pasar la respuesta sin más:
 * forzarlas por este umbral las bloquearía siempre, porque nunca traen puntaje de reranker.
 *
 * <p>El piso de rechazo crece con el tamaño del corpus del proyecto (interpolación lineal entre
 * {@code piso} y {@code techo} sobre {@code chunksReferencia}): con pocos chunks — el corpus
 * semilla del taller, ~4 — los puntajes del cross-encoder se comprimen cerca de cero incluso para
 * una coincidencia real, así que empezar exigiendo {@code techo} bloquearía también las respuestas
 * buenas. Es una aproximación heurística sin más validación que la de ADR-0008 — no una curva
 * calibrada con datos de producción.
 *
 * <p>Por encima de ese piso, un solo score no alcanza a distinguir "relevante de verdad" de
 * "comparte vocabulario superficial" — ADR-0008 documenta un contraejemplo real donde una pregunta
 * irrelevante puntuó más alto que una relevante. Por eso el resultado tiene un tercer estado,
 * {@code AMBIGUO}: ni se rechaza ni se acepta solo con el score, se manda a {@code
 * VerificadorGrounding} para una segunda opinión antes de sintetizar. Solo se salta esa
 * verificación cuando el score supera {@code techoConfianza} — prácticamente una copia literal,
 * donde no hay ambigüedad real que resolver.
 */
final class UmbralRelevancia {

  private UmbralRelevancia() {}

  enum Decision {
    SUFICIENTE,
    AMBIGUO,
    INSUFICIENTE
  }

  record Resultado(Decision decision, double umbralUsado, Double mejorPuntaje) {}

  static double umbralEfectivo(long chunksProyecto, UmbralRelevanciaPropiedades props) {
    double proporcion =
        Math.min(1.0, chunksProyecto / (double) Math.max(1, props.chunksReferencia()));
    return props.piso() + (props.techo() - props.piso()) * proporcion;
  }

  static Resultado evaluar(
      List<Fragmento> fragmentos, long chunksProyecto, UmbralRelevanciaPropiedades props) {
    if (!props.habilitado()) {
      return new Resultado(Decision.SUFICIENTE, 0.0, null);
    }

    List<Double> puntajesRankeados =
        fragmentos.stream().map(Fragmento::rerank).filter(Objects::nonNull).toList();
    boolean hayFragmentosSinRankear = fragmentos.size() > puntajesRankeados.size();
    if (hayFragmentosSinRankear) {
      return new Resultado(Decision.SUFICIENTE, 0.0, null);
    }

    double umbral = umbralEfectivo(chunksProyecto, props);
    if (puntajesRankeados.isEmpty()) {
      return new Resultado(Decision.INSUFICIENTE, umbral, null);
    }

    double mejor = puntajesRankeados.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    if (mejor < umbral) {
      return new Resultado(Decision.INSUFICIENTE, umbral, mejor);
    }
    if (mejor >= props.techoConfianza()) {
      return new Resultado(Decision.SUFICIENTE, umbral, mejor);
    }
    return new Resultado(Decision.AMBIGUO, umbral, mejor);
  }
}
