package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone el pipeline de siete etapas de F3 por su propia cuenta, antes de que exista un adaptador
 * de verdad (F4 web, F5 Teams). Igual que {@code IngestaController} y {@code
 * RecuperacionController} en sus módulos: un endpoint operativo dentro del propio módulo, no en
 * {@code web} — la regla de ArchUnit que aísla a los adaptadores no le aplica.
 *
 * <p>Devuelve la {@link Orquestador.EjecucionPipeline} completa, no solo la {@code Respuesta}: el
 * criterio de salida de F3 pide la traza de las siete etapas, no solo el texto final. Los
 * adaptadores de F4/F5, en cambio, hablan con {@link Consultar} y nunca ven esta forma.
 */
@RestController
class OrquestacionController {

  private final Orquestador orquestador;

  OrquestacionController(Orquestador orquestador) {
    this.orquestador = orquestador;
  }

  record ConsultaPregunta(@NotBlank String q, String projectId) {}

  @PostMapping("/api/ask")
  Orquestador.EjecucionPipeline preguntar(@Valid @RequestBody ConsultaPregunta consulta) {
    String proyecto =
        (consulta.projectId() == null || consulta.projectId().isBlank())
            ? ProyectoId.POR_DEFECTO.valor()
            : consulta.projectId();
    return orquestador.ejecutar(
        new Pregunta(consulta.q()), new ProyectoId(proyecto), Filtros.NINGUNO);
  }
}
