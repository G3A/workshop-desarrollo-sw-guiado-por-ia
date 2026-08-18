package co.g3a.baseconocimiento.recuperacion;

import java.util.List;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;

/**
 * La única puerta pública de {@code recuperacion}: el resto del módulo
 * (repositorio, señales, fusión) queda package-private a propósito, igual que
 * {@code Consultar} es la única puerta de {@code orquestacion}. Las
 * herramientas {@code search_unified} y {@code search_docs} de F3 llaman aquí,
 * no a {@link Recuperador} directamente.
 */
public interface Buscador {

    /**
     * Las cuatro señales sobre el corpus del proyecto. Solo considera chunks
     * cuyo {@code kind} esté en {@code tiposPermitidos} y cuyo documento esté
     * en {@code documentosPermitidos}; una lista vacía en cualquiera de los dos
     * equivale a no filtrar por ese eje.
     */
    List<ResultadoBusqueda> buscar(
            String consulta, String projectId, List<String> tiposPermitidos, List<Long> documentosPermitidos);

    /**
     * Solo la señal 1 (texto completo): sin embedding, sin IDF, sin decaimiento
     * ni cross-encoder. Existe para el adaptador web de F4 — el "keyword search
     * on landing" del artículo: algo que responde casi al instante mientras el
     * pipeline completo de siete etapas todavía está corriendo detrás.
     */
    List<Fragmento> buscarPalabraClave(
            String consulta, String projectId, List<Long> documentosPermitidos, int limite);
}
