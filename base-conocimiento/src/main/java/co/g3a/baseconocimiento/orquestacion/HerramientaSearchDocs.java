package co.g3a.baseconocimiento.orquestacion;

import java.util.List;

import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.recuperacion.Buscador;
import co.g3a.baseconocimiento.recuperacion.ResultadoBusqueda;

/**
 * La misma búsqueda híbrida, pero restringida a documentación y wiki — nunca
 * código ni hilos. Útil cuando la pregunta es claramente sobre "qué dice la
 * documentación", donde mezclar con código solo añade ruido.
 */
@Component
class HerramientaSearchDocs implements Herramienta {

    private static final List<String> TIPOS = List.of("doc_section", "wiki_section");

    private final Buscador buscador;

    HerramientaSearchDocs(Buscador buscador) {
        this.buscador = buscador;
    }

    @Override
    public String nombre() {
        return "search_docs";
    }

    @Override
    public String descripcion() {
        return "Busqueda hibrida restringida a documentacion y paginas de wiki (excluye codigo "
                + "e hilos). Preferible cuando la pregunta es sobre que dice la documentacion.";
    }

    @Override
    public List<Fragmento> ejecutar(String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
        return buscador.buscar(consulta, proyecto.valor(), TIPOS, documentosPermitidos).stream()
                .map(ResultadoBusqueda::fragmento).toList();
    }
}
