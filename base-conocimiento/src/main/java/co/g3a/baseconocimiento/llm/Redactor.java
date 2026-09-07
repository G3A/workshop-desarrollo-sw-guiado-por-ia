package co.g3a.baseconocimiento.llm;

import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Lo que el modulo {@code acciones} le pide al LLM: redactar a partir de un contexto ya armado
 * (resumir, sintetizar, preguntas, ideas), traducir un texto y decir en que idioma esta escrito.
 *
 * <p>Es el hermano del {@link Sintetizador} para trabajos que no son responder una pregunta: el
 * prompt de sintesis exige «responder SOLO la pregunta» y «decir que no alcanza», dos reglas que
 * aqui no aplican y que un modelo chico obedeceria igual, negandose a resumir. Por eso son metodos
 * aparte con prompts propios, y por eso no reciben ningun tipo del modulo {@code acciones}: igual
 * que {@link Sintetizador} recibe texto plano, este recibe un contexto ya numerado con {@code [n]}
 * y devuelve texto o records propios. Un enum de {@code acciones} aqui seria un ciclo entre
 * modulos.
 *
 * <p>Los idiomas son codigos ISO 639-1 ({@code "es"}, {@code "en"}); {@code "und"} significa «no se
 * pudo determinar» y quien llama decide que hacer con eso.
 */
public interface Redactor {

  /**
   * Una pregunta que los documentos permiten hacer; {@code fuente} es el {@code n} de {@code [n]}.
   */
  record Pregunta(String texto, int fuente) {}

  record Tema(String tema, List<Pregunta> preguntas) {}

  /** Salida estructurada de {@link #preguntar}; vacia si el modelo no devolvio algo valido. */
  record Preguntas(List<Tema> temas) {}

  record Idea(String titulo, String justificacion, int fuente) {}

  /** Salida estructurada de {@link #idear}; vacia si el modelo no devolvio algo valido. */
  record Ideas(List<Idea> ideas) {}

  /** Un resumen por documento del contexto, en streaming, con {@code [n]} por afirmacion. */
  Flux<String> resumir(String contexto, String idioma);

  /** Un solo texto que cruza los documentos: en comun, contradicciones, conclusion. */
  Flux<String> sintetizar(String contexto, String idioma);

  Preguntas preguntar(String contexto, String idioma);

  Ideas idear(String contexto, String idioma);

  /**
   * Solo la traduccion, conservando el Markdown y los marcadores {@code [n]} del original.
   *
   * @param origen codigo ISO 639-1 o {@code "und"} (el modelo lo infiere del texto)
   */
  Flux<String> traducir(String texto, String origen, String destino);

  /** Codigo ISO 639-1 del idioma de {@code muestra}, o {@code "und"} si no se pudo determinar. */
  String detectarIdioma(String muestra);
}
