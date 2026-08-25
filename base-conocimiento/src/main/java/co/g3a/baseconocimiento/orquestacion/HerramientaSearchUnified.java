package co.g3a.baseconocimiento.orquestacion;

import java.util.List;

import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.recuperacion.Buscador;
import co.g3a.baseconocimiento.recuperacion.ResultadoBusqueda;

/**
 * La búsqueda híbrida de F2 completa, sin restringir por tipo de chunk: las
 * cuatro señales, RRF y el cross-encoder. La opción por defecto cuando el
 * planner duda.
 */
@Component
class HerramientaSearchUnified implements Herramienta {

    private final Buscador buscador;

    HerramientaSearchUnified(Buscador buscador) {
        this.buscador = buscador;
    }

    @Override
    public String nombre() {
        return "search_unified";
    }

    @Override
    public String descripcion() {
        return "Busqueda hibrida general (texto completo, semantica, terminos raros y frescura, "
                + "con reranking por cross-encoder). Es la opcion por defecto: casi cualquier "
                + "pregunta se beneficia de incluirla.";
    }

    @Override
    public List<Fragmento> ejecutar(String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
        return buscador.buscar(consulta, proyecto.valor(), List.of(), documentosPermitidos).stream()
                .map(ResultadoBusqueda::fragmento).toList();
    }
}
