package co.g3a.baseconocimiento.recuperacion;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;

/**
 * Un resultado final más la traza que pide el criterio de salida de F2: en
 * qué rango llegó al reranker tras la fusión RRF, y en cuál salió después.
 *
 * <p>Público porque es lo que {@link Buscador} devuelve a otros módulos
 * (las herramientas {@code search_unified} y {@code search_docs} de F3).
 */
public record ResultadoBusqueda(Fragmento fragmento, int rangoRrf, int rangoFinal) {
}
