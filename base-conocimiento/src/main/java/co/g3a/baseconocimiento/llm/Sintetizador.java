package co.g3a.baseconocimiento.llm;

import co.g3a.baseconocimiento.compartido.Dominio.IdiomaRespuesta;
import reactor.core.publisher.Flux;

/**
 * La síntesis final del pipeline de siete etapas: redacta la respuesta en streaming a partir del
 * contexto que {@code orquestacion} ya armó (fragmentos elegidos, expandidos con sus vecinos, con
 * su marcador de cita {@code [n]}).
 *
 * <p>No sabe qué es un {@code Fragmento} ni una {@code Cita} — recibe texto plano, igual que {@link
 * Planificador} recibe un catálogo como {@code Map<String, String>}. La obligación de citar y de
 * señalar contradicciones vive en el prompt de sistema de la implementación, no en el tipo de esta
 * interfaz.
 *
 * @see IdiomaRespuesta el único conocimiento de dominio que sí recibe: en qué idioma redactar
 */
public interface Sintetizador {

  Flux<String> sintetizar(String pregunta, String contexto, IdiomaRespuesta idioma);
}
