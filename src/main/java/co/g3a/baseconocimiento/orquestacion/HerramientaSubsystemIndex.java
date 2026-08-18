package co.g3a.baseconocimiento.orquestacion;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;

/**
 * Un índice de qué subsistemas cubre el corpus, agregando
 * {@code distilled.systems_mentioned}.
 *
 * <p>Ese campo lo puebla la destilación por LLM de hilos de Teams (F6); para
 * documentos y código de hoy, cuya "destilación" es heurística (ver
 * {@code package-info} de {@code ingesta}), esta herramienta devuelve vacío —
 * no hay nada que agregar todavía, y eso es honesto, no un error.
 */
@Component
class HerramientaSubsystemIndex implements Herramienta {

    private final HerramientasRepositorio repo;
    private final int limite;

    HerramientaSubsystemIndex(
            HerramientasRepositorio repo,
            @Value("${kb.orquestacion.herramientas.subsystem-index.limite:15}") int limite) {
        this.repo = repo;
        this.limite = limite;
    }

    @Override
    public String nombre() {
        return "subsystem_index";
    }

    @Override
    public String descripcion() {
        return "Indice de que subsistemas o componentes cubre el corpus y cuantas veces se "
                + "mencionan. Util para preguntas de panorama general, no de detalle puntual.";
    }

    @Override
    public List<Fragmento> ejecutar(String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
        return repo.indiceDeSubsistemas(proyecto.valor(), limite);
    }
}
