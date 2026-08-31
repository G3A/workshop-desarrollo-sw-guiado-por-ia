package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.recuperacion.Buscador;
import co.g3a.baseconocimiento.recuperacion.ResultadoBusqueda;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Encuentra qué documentos son la autoridad sobre un tema: la búsqueda híbrida, agrupada por
 * documento y quedándose con el mejor fragmento de cada uno.
 *
 * <p>Es una aproximación honesta a lo que el nombre promete. El artículo de Cerebras responde
 * "quién sabe de esto" con participantes reales de Slack; aquí, hasta que existan los conectores de
 * Teams y Git (F6) con metadatos de autoría, la mejor respuesta disponible es "qué documento cubre
 * esto mejor" — no un invento de una persona que el sistema no puede verificar.
 */
@Component
class HerramientaWhoKnows implements Herramienta {

  private final Buscador buscador;

  HerramientaWhoKnows(Buscador buscador) {
    this.buscador = buscador;
  }

  @Override
  public String nombre() {
    return "who_knows";
  }

  @Override
  public String descripcion() {
    return "Encuentra que documentos son la autoridad sobre un tema (por cobertura de "
        + "contenido). Util para preguntas del tipo \"donde esta documentado X\".";
  }

  @Override
  public List<Fragmento> ejecutar(
      String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
    List<Fragmento> todos =
        buscador.buscar(consulta, proyecto.valor(), List.of(), documentosPermitidos).stream()
            .map(ResultadoBusqueda::fragmento)
            .toList();

    Map<Long, Fragmento> mejorPorDocumento = new LinkedHashMap<>();
    for (Fragmento f : todos) {
      mejorPorDocumento.merge(f.documentoId(), f, HerramientaWhoKnows::masFuerte);
    }
    return List.copyOf(mejorPorDocumento.values());
  }

  private static Fragmento masFuerte(Fragmento a, Fragmento b) {
    double puntajeA = a.rerank() != null ? a.rerank() : a.rrf();
    double puntajeB = b.rerank() != null ? b.rerank() : b.rrf();
    return puntajeB > puntajeA ? b : a;
  }
}
