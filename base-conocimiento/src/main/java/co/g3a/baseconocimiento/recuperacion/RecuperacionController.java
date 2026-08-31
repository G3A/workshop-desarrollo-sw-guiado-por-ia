package co.g3a.baseconocimiento.recuperacion;

import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone el retrieval híbrido de F2 por su propia cuenta, antes de que exista el planner de F3.
 * Vive dentro de {@code recuperacion}, no en {@code web}: es un endpoint de esta etapa sobre sí
 * misma, igual que {@code IngestaController} dentro de {@code ingesta} — la regla de ArchUnit que
 * aísla a los adaptadores no aplica a estos controllers operativos.
 */
@RestController
class RecuperacionController {

  private final Buscador buscador;

  RecuperacionController(Buscador buscador) {
    this.buscador = buscador;
  }

  record ConsultaBusqueda(@NotBlank String q, String projectId, List<String> tipos) {}

  @PostMapping("/api/search")
  List<ResultadoBusqueda> buscar(@Valid @RequestBody ConsultaBusqueda consulta) {
    String proyecto =
        (consulta.projectId() == null || consulta.projectId().isBlank())
            ? ProyectoId.POR_DEFECTO.valor()
            : consulta.projectId();
    List<String> tipos = consulta.tipos() == null ? List.of() : consulta.tipos();
    return buscador.buscar(consulta.q(), proyecto, tipos, List.of());
  }
}
