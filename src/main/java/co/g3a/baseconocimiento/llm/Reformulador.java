package co.g3a.baseconocimiento.llm;

/**
 * Propone, si hace falta, una consulta de búsqueda alternativa que use el
 * vocabulario y el idioma más probable de la documentación fuente —
 * independiente de {@link Planificador}, que solo decide QUÉ herramientas
 * correr y nunca toca el texto de la pregunta.
 *
 * <p>Existe porque una pregunta con vocabulario coloquial ("autoboxing") puede
 * no encontrar un fragmento que sí existe en el corpus bajo el término formal
 * de la fuente ("boxing conversion", JLS) — la recuperación híbrida no hace
 * ninguna reescritura de consulta antes de este punto (investigación de VRAM
 * y modelo LLM, sesión 17).
 */
public interface Reformulador {

    /**
     * @param textoBusqueda la consulta a usar contra las herramientas de búsqueda —
     *                       igual a la pregunta original si {@code reformulada} es {@code false}
     * @param reformulada    {@code true} solo si de verdad cambió el texto, para que el
     *                       llamador no tenga que comparar strings para saber si mostrarlo
     */
    record Reformulacion(String textoBusqueda, boolean reformulada) {
    }

    Reformulacion reformular(String pregunta);
}
