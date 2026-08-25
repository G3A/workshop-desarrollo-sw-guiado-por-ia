package co.g3a.baseconocimiento.llm;

import java.util.List;
import java.util.Map;

/**
 * Decide qué herramientas ejecutar para responder una pregunta — la primera
 * etapa del pipeline de siete de F3.
 *
 * <p>Deliberadamente ignora qué son las herramientas: recibe su catálogo como
 * {@code nombre -> descripción} en cada llamada. El dueño de ese catálogo (las
 * seis herramientas concretas) es {@code orquestacion}, no este módulo — así
 * {@code llm} sigue siendo, como dice su {@code package-info}, solo un cliente
 * de chat, sin conocer un ápice de dominio.
 */
public interface Planificador {

    /**
     * @param herramientas nombres de las herramientas elegidas; nunca {@code null},
     *                      puede estar vacía si el planner no encontró ninguna aplicable
     * @param razon         una frase breve de por qué, útil en la traza de {@code query_log}
     */
    record PlanDeHerramientas(List<String> herramientas, String razon) {
    }

    PlanDeHerramientas planificar(String pregunta, Map<String, String> herramientasDisponibles);
}
