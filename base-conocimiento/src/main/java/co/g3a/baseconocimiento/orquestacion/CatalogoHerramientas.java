package co.g3a.baseconocimiento.orquestacion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * El registro de las seis herramientas, indexado por nombre. Spring inyecta automáticamente todos
 * los beans {@link Herramienta} en la lista del constructor — agregar una séptima herramienta es
 * escribir la clase y anotarla {@code @Component}, nada más.
 */
@Component
class CatalogoHerramientas {

  private final Map<String, Herramienta> porNombre;

  CatalogoHerramientas(List<Herramienta> herramientas) {
    Map<String, Herramienta> indice = new LinkedHashMap<>();
    herramientas.forEach(h -> indice.put(h.nombre(), h));
    this.porNombre = Map.copyOf(indice);
  }

  /** {@code nombre -> descripcion}, lo que el planner recibe como catálogo. */
  Map<String, String> descripciones() {
    Map<String, String> resultado = new LinkedHashMap<>();
    porNombre.forEach((nombre, h) -> resultado.put(nombre, h.descripcion()));
    return resultado;
  }

  Optional<Herramienta> porNombre(String nombre) {
    return Optional.ofNullable(porNombre.get(nombre));
  }
}
