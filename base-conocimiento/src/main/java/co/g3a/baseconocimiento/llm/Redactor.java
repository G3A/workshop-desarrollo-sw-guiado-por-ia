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
   * Los seis niveles de la taxonomia de Bloom, en orden (sub-issue #61): las preguntas se generan
   * nivel por nivel. La descripcion y los ejemplos de arranque son los que ve el modelo en su
   * prompt: a un modelo chico le hace falta ver como empieza una pregunta de cada nivel.
   */
  enum NivelBloom {
    RECORDAR(
        "recordar",
        "reconocer y recuperar hechos, terminos, datos y pasos tal como aparecen",
        "\"¿Qué es...?\", \"¿Cuál es el comando para...?\", \"¿Dónde se configura...?\""),
    COMPRENDER(
        "comprender",
        "explicar con otras palabras, resumir, clasificar, dar ejemplos",
        "\"¿Por qué hace falta...?\", \"¿Qué significa que...?\", \"¿Cómo se relaciona...?\""),
    APLICAR(
        "aplicar",
        "usar lo que dice el documento en una situacion concreta o un caso nuevo",
        "\"¿Cómo harías... si...?\", \"¿Qué pasaría si...?\", \"¿Qué pasos seguirías para...?\""),
    ANALIZAR(
        "analizar",
        "descomponer, comparar, encontrar relaciones, causas y supuestos",
        "\"¿Qué diferencia hay entre...?\", \"¿Por qué el documento supone...?\", \"¿Qué"
            + " depende de...?\""),
    EVALUAR(
        "evaluar",
        "juzgar con criterios: ventajas, riesgos, decisiones y su justificacion",
        "\"¿Es mejor... o...? ¿Por qué?\", \"¿Qué riesgo tiene...?\", \"¿Cuándo no conviene...?\""),
    CREAR(
        "crear",
        "proponer algo nuevo a partir del documento: un plan, una mejora, una alternativa",
        "\"¿Cómo diseñarías...?\", \"¿Qué alternativa propondrías a...?\", \"¿Qué agregarías"
            + " para...?\"");

    private final String codigo;
    private final String descripcion;
    private final String ejemplos;

    NivelBloom(String codigo, String descripcion, String ejemplos) {
      this.codigo = codigo;
      this.descripcion = descripcion;
      this.ejemplos = ejemplos;
    }

    public String codigo() {
      return codigo;
    }

    public String descripcion() {
      return descripcion;
    }

    public String ejemplos() {
      return ejemplos;
    }
  }

  /**
   * Una pregunta que los documentos permiten hacer. {@code tipo} es el interrogativo 5W1H con que
   * se formulo ({@code que}, {@code quien}, {@code cuando}, {@code donde}, {@code por-que}, {@code
   * como}; vacio si el modelo no lo dijo); {@code fuente} es el {@code n} de {@code [n]}.
   */
  record Pregunta(String texto, String tipo, int fuente) {}

  /** Las preguntas de un nivel de Bloom; vacia si los documentos no dan para ese nivel. */
  record Nivel(String nivel, List<Pregunta> preguntas) {}

  /** Lo que la accion acumula nivel a nivel: un {@link Nivel} por nivel ya generado, en orden. */
  record Preguntas(List<Nivel> niveles) {}

  record Idea(String titulo, String justificacion, int fuente) {}

  /** Salida estructurada de {@link #idear}; vacia si el modelo no devolvio algo valido. */
  record Ideas(List<Idea> ideas) {}

  /** Un resumen por documento del contexto, en streaming, con {@code [n]} por afirmacion. */
  Flux<String> resumir(String contexto, String idioma);

  /** Un solo texto que cruza los documentos: en comun, contradicciones, conclusion. */
  Flux<String> sintetizar(String contexto, String idioma);

  /**
   * Las preguntas de UN nivel de Bloom: por cada interrogativo 5W1H una como maximo, y solo si un
   * documento la responde. Solo cuentan los textos que son preguntas (con signo de interrogacion) y
   * que no repiten ninguna de {@code yaFormuladas}, las de los niveles anteriores. Vacia si no hay
   * o si el modelo no devolvio algo valido. Bloqueante.
   */
  List<Pregunta> preguntar(
      String contexto, String idioma, NivelBloom nivel, List<String> yaFormuladas);

  Ideas idear(String contexto, String idioma);

  /**
   * Notas de lectura de un tramo de un documento largo, para que otra llamada las use en lugar del
   * texto original: fieles, en el orden del texto, sin agregar nada. Bloqueante.
   *
   * @param maxCaracteres largo que se le pide al modelo (quien llama recorta si se pasa)
   */
  String condensar(String tramo, String idioma, int maxCaracteres);

  /**
   * Solo la traduccion, conservando el Markdown y los marcadores {@code [n]} del original.
   *
   * @param origen codigo ISO 639-1 o {@code "und"} (el modelo lo infiere del texto)
   */
  Flux<String> traducir(String texto, String origen, String destino);

  /** Codigo ISO 639-1 del idioma de {@code muestra}, o {@code "und"} si no se pudo determinar. */
  String detectarIdioma(String muestra);
}
