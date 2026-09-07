package co.g3a.baseconocimiento.ingesta;

import co.g3a.baseconocimiento.ingesta.RelevadorDeFuentes.ResultadoRelevo;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dispara la ingesta a mano. Vive dentro de {@code ingesta}, no en {@code web}: es un endpoint de
 * operación que el propio módulo expone sobre sí mismo, no una puerta de usuario final — por eso la
 * regla de ArchUnit que aísla a los adaptadores no le aplica.
 *
 * <p>{@code make ingest} le apunta directo. Delega en {@link RelevadorDeFuentes} en vez de llamar a
 * cada conector directo (como hacía antes de F8): así una llamada manual, el relevo periódico y el
 * botón "reindexar ahora" de la consola de F9 comparten el mismo candado por tipo, y no pueden
 * pisarse.
 */
@RestController
class IngestaController {

  private final RelevadorDeFuentes relevador;

  IngestaController(RelevadorDeFuentes relevador) {
    this.relevador = relevador;
  }

  @PostMapping("/api/ingest/local-docs")
  ResultadoRelevo ingerirDocumentosLocales() {
    return relevador.relevar(RelevadorDeFuentes.LOCAL_DOCS);
  }

  @PostMapping("/api/ingest/repos-locales")
  ResultadoRelevo ingerirReposLocales() {
    return relevador.relevar(RelevadorDeFuentes.LOCAL_GIT);
  }

  /** No-op (resultado con un {@code Resumen} en ceros) si {@code kb.graph.habilitado=false}. */
  @PostMapping("/api/ingest/teams-graph")
  ResultadoRelevo ingerirTeamsGraph() {
    return relevador.relevar(RelevadorDeFuentes.TEAMS_CHANNEL);
  }

  /** No-op (resultado con un {@code Resumen} en ceros) si {@code kb.azdo.habilitado=false}. */
  @PostMapping("/api/ingest/azure-devops")
  ResultadoRelevo ingerirAzureDevOps() {
    return relevador.relevar(RelevadorDeFuentes.AZURE_DEVOPS);
  }
}
