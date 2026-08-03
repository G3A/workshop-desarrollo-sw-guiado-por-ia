package co.g3a.baseconocimiento.orquestacion;

import java.util.List;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;

/**
 * Una de las seis herramientas que el planner puede elegir. Todas devuelven lo
 * mismo — {@code List<Fragmento>} — sin importar si por debajo consultan la
 * tabla {@code chunks} (search_docs, search_unified, who_knows), agregan
 * metadatos ({@code recent_commits}, {@code subsystem_index}) o invocan un
 * proceso externo ({@code search_code} con ripgrep): esa uniformidad es lo que
 * permite que el {@link Executor} y la fusión de fragmentos no necesiten saber
 * qué herramienta produjo cada resultado.
 */
interface Herramienta {

    /** El nombre que el planner usa para elegirla; debe coincidir con lo que ve en su prompt. */
    String nombre();

    /** Una frase breve para el catálogo que el planner recibe en cada consulta. */
    String descripcion();

    /**
     * Nunca lanza para errores esperables (sin resultados, fuente no configurada
     * todavía): devuelve una lista vacía. El {@link Executor} solo aísla fallos
     * inesperados (Postgres caído, proceso externo que no arranca).
     */
    List<Fragmento> ejecutar(String consulta, ProyectoId proyecto);
}
