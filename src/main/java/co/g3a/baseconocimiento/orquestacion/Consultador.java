package co.g3a.baseconocimiento.orquestacion;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;
import co.g3a.baseconocimiento.recuperacion.Buscador;

/**
 * La implementación de {@link Consultar}: delega las siete etapas a
 * {@link Orquestador} y la vista previa a {@link Buscador} directamente — esa
 * no es una de las siete etapas, es una capacidad aparte que se apoya en
 * {@code recuperacion} sin pasar por el planner ni el executor.
 *
 * <p>Los adaptadores de F4/F5 dependen de esta interfaz, nunca de
 * {@code Orquestador} — así no ven el plan, la traza por herramienta ni el id
 * de {@code query_log}.
 */
@Component
class Consultador implements Consultar {

    private final Orquestador orquestador;
    private final Buscador buscador;

    Consultador(Orquestador orquestador, Buscador buscador) {
        this.orquestador = orquestador;
        this.buscador = buscador;
    }

    @Override
    public Respuesta responder(Pregunta pregunta, ProyectoId proyecto, Filtros filtros) {
        return orquestador.ejecutar(pregunta, proyecto, filtros).respuesta();
    }

    @Override
    public RespuestaEnStreaming responderEnStreaming(
            Pregunta pregunta, ProyectoId proyecto, Filtros filtros, Long conversacionId) {
        return orquestador.ejecutarEnStreaming(pregunta, proyecto, filtros, conversacionId);
    }

    @Override
    public Optional<EstadoStream> estadoDeStream(long conversacionId) {
        return orquestador.estadoDeStream(conversacionId)
                .map(e -> new EstadoStream(e.estado(), e.pregunta(), e.projectId(), e.texto(), e.citas(), e.reformulacion()));
    }

    @Override
    public List<Cita> previsualizar(Pregunta pregunta, ProyectoId proyecto, int limite, List<Long> documentosPermitidos) {
        return buscador.buscarPalabraClave(pregunta.texto(), proyecto.valor(), documentosPermitidos, limite).stream()
                .map(Citas::desde)
                .toList();
    }
}
