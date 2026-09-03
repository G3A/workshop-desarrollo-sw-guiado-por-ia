package co.g3a.baseconocimiento.llm;

import java.util.List;

/**
 * Propone, si hace falta, consultas de búsqueda alternativas que usen el vocabulario y el idioma
 * más probable de la documentación fuente — independiente de {@link Planificador}, que solo decide
 * QUÉ herramientas correr y nunca toca el texto de la pregunta.
 *
 * <p>Existe porque una pregunta con vocabulario coloquial ("autoboxing") puede no encontrar un
 * fragmento que sí existe en el corpus bajo el término formal de la fuente ("boxing conversion",
 * JLS) — la recuperación híbrida no hace ninguna reescritura de consulta antes de este punto
 * (investigación de VRAM y modelo LLM, sesión 17).
 *
 * <p>Devuelve VARIAS alternativas, no una, porque el adaptador web se las muestra a la persona para
 * que elija con cuál buscar (ver {@code Consultar.ModoReformulacion.Proponer}); el camino
 * automático (Teams, {@code /api/ask}) sigue usando solo la primera, como antes.
 */
public interface Reformulador {

  /**
   * @param pregunta la pregunta original, tal cual llegó
   * @param alternativas consultas de búsqueda distintas de la pregunta, de la más probable a la
   *     menos; vacía si no hace falta reformular (o si el LLM no respondió)
   */
  record Reformulacion(String pregunta, List<String> alternativas) {

    public Reformulacion {
      alternativas = List.copyOf(alternativas);
    }

    public static Reformulacion sinCambios(String pregunta) {
      return new Reformulacion(pregunta, List.of());
    }

    /** {@code true} solo si hay al menos una alternativa que de verdad difiere de la pregunta. */
    public boolean reformulada() {
      return !alternativas.isEmpty();
    }

    /**
     * La consulta que usa el camino automático: la primera alternativa, o la pregunta tal cual si
     * no hay ninguna.
     */
    public String textoBusqueda() {
      return reformulada() ? alternativas.getFirst() : pregunta;
    }
  }

  Reformulacion reformular(String pregunta);
}
