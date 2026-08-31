package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Lista los cambios más recientes en repos de código conectados.
 *
 * <p>Consulta {@code documents} de fuentes {@code local_git}, no invoca Git en vivo: el conector
 * que puebla esas filas es F6. Hasta entonces esta herramienta devuelve una lista vacía — el
 * comportamiento correcto para un conector deshabilitado, no un error.
 */
@Component
class HerramientaRecentCommits implements Herramienta {

  private final HerramientasRepositorio repo;
  private final int limite;

  HerramientaRecentCommits(
      HerramientasRepositorio repo,
      @Value("${kb.orquestacion.herramientas.recent-commits.limite:10}") int limite) {
    this.repo = repo;
    this.limite = limite;
  }

  @Override
  public String nombre() {
    return "recent_commits";
  }

  @Override
  public String descripcion() {
    return "Lista los cambios mas recientes en los repos de codigo conectados. Util para "
        + "preguntas sobre que cambio recientemente.";
  }

  @Override
  public List<Fragmento> ejecutar(
      String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
    return repo.masRecientesPorFuente("local_git", proyecto.valor(), limite);
  }
}
