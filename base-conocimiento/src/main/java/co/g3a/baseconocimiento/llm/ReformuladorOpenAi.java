package co.g3a.baseconocimiento.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * {@code maxTokens(240)}: hasta tres consultas reformuladas, cada una una frase de búsqueda corta,
 * no una respuesta -- mismo espíritu que el tope de {@link PlanificadorOpenAi} para el campo {@code
 * razon}. Era 120 cuando se pedía una sola alternativa.
 *
 * <p>{@code extraBody(reasoning_effort=none)}: ver {@link PlanificadorOpenAi} -- este componente se
 * sumó en la sesión 17 de la investigación (ver docs/investigacion-vram-y-modelo-llm.md), después
 * de que la sesión 18 documentara el fix, así que había quedado sin el ajuste. Medido en vivo con
 * la sesión 20 (perfil {@code qwen3.5:4b}): sin esto, el mismo síntoma de "thinking" se comía el
 * {@code maxTokens} y el log mostraba "Fallo al reformular la consulta, se usa la pregunta
 * original" (JSON vacío, mismo {@code MismatchedInputException} de los hallazgos 74/75) -- benigno
 * porque el catch de abajo ya usa la pregunta original como respaldo, pero desperdiciaba la llamada
 * entera. No lleva {@code repeat_penalty} como los otros tres componentes: sin evidencia propia de
 * que este haga falta aquí.
 *
 * <p>Arma su propio {@link ChatClient.Builder} en vez de un bean compartido, por la misma razón
 * documentada en {@link PlanificadorOpenAi}: un {@code ChatClient.Builder} inyectado es mutable y
 * compartirlo entre componentes con distinto {@code maxTokens} termina con todos usando el del
 * último bean que Spring inicializa.
 */
@Component
class ReformuladorOpenAi implements Reformulador {

  private static final Logger log = LoggerFactory.getLogger(ReformuladorOpenAi.class);

  static final int MAX_ALTERNATIVAS = 3;

  /**
   * Pide ESTRATEGIAS distintas, no paráfrasis. Medido en vivo (granite4.1:3b, corpus con la JLS,
   * pregunta "qué significa cada scope de usar static dentro de una clase java"): con el prompt
   * anterior las tres alternativas eran traducciones de la misma frase ("what is the meaning of
   * each scope of using static...") y las cuatro variantes, incluida la original, terminaban en "No
   * encontré información" con rerank máximo 5.6. Las mismas ideas escritas con el vocabulario de la
   * fuente y descompuestas ("static field class variable static modifier", "static method class
   * method", "static member class nested class", "static initializer") dieron rerank 9.1 a 10.0 y
   * seis citas cada una. El cuello de botella era la pregunta, no el corpus.
   */
  private static final String SISTEMA =
      """
            Eres un asistente de reformulacion de consultas para una busqueda hibrida
            (texto + vectorial) contra una base de conocimiento tecnica. La tarea NO es
            responder la pregunta -- es proponer, solo si hace falta, hasta tres CONSULTAS DE
            BUSQUEDA alternativas que maximicen las coincidencias lexicas y semanticas con el
            texto fuente.

            La documentacion tecnica de referencia (especificaciones de lenguajes, estandares,
            RFCs, manuales oficiales) suele estar en ingles formal y nombrar las cosas con su
            termino oficial, aunque la pregunta llegue en español y con jerga. Una consulta
            buena usa ESAS palabras: los terminos exactos que aparecerian en la fuente.

            NO propongas parafrasis: traducir la pregunta entera o reordenar sus palabras no
            sirve, porque sigue sin contener los terminos de la fuente. Cada alternativa debe
            seguir una ESTRATEGIA distinta:

            1. TERMINO FORMAL: el nombre oficial del concepto en la fuente, sin las palabras
               de relleno de la pregunta. Ejemplo: "que es el autoboxing" -> "boxing conversion".
            2. DESCOMPOSICION: si la pregunta abarca varios conceptos ("cada tipo de...",
               "todos los usos de...", "que diferencias hay entre..."), una consulta por el
               concepto mas concreto, con su termino formal. Ejemplo: "que significa cada scope
               de usar static dentro de una clase java" -> "static field class variable static
               modifier", "static method class method", "static member class nested class".
            3. SINONIMOS OFICIALES: otra forma en que la misma fuente nombra ese concepto
               (nombre de la seccion, palabra clave del lenguaje, sigla o su expansion).

            Cada consulta: una frase corta de palabras clave (3 a 8 palabras), en el idioma de la
            fuente, sin signos de interrogacion, sin "que es" ni "como se", nunca una respuesta.
            Ordenalas de la mas probable a la menos. Deben ser distintas entre si.

            Si la pregunta ya usa terminologia formal, o no hay forma razonable de saber que
            terminos usa la fuente, devuelve la lista VACIA. No inventes terminologia de la que
            no haya certeza razonable -- es preferible no reformular a reformular mal.
            """;

  /** La forma en que el modelo responde: solo las consultas, sin la pregunta ni banderas. */
  record Propuestas(List<String> consultas) {}

  private final ChatClient chatClient;

  ReformuladorOpenAi(OpenAiChatModel modelo) {
    var opciones =
        OpenAiChatOptions.builder().extraBody(Map.of("reasoning_effort", "none")).maxTokens(240);
    this.chatClient = ChatClient.builder(modelo).defaultOptions(opciones).build();
  }

  @Override
  public Reformulacion reformular(String pregunta) {
    try {
      Propuestas resultado =
          chatClient
              .prompt()
              .system(SISTEMA)
              .user(pregunta)
              .call()
              .entity(Propuestas.class, spec -> spec.useProviderStructuredOutput());

      if (resultado == null || resultado.consultas() == null) {
        return Reformulacion.sinCambios(pregunta);
      }
      return new Reformulacion(pregunta, depurar(pregunta, resultado.consultas()));
    } catch (Exception e) {
      // Igual que el planner: la reformulacion nunca debe tumbar la pregunta. Si
      // llama-server no responde o el JSON no valida, se busca con el texto
      // original -- ni mejor ni peor que el comportamiento previo a este componente.
      log.warn("Fallo al reformular la consulta, se usa la pregunta original: {}", e.toString());
      return Reformulacion.sinCambios(pregunta);
    }
  }

  /**
   * Se queda solo con las candidatas que de verdad difieren de la pregunta y entre sí, en el orden
   * en que llegaron, hasta {@link #MAX_ALTERNATIVAS}.
   *
   * <p>Comparar el texto es la única señal que no depende de que el modelo "sepa" que reformuló:
   * medido en vivo con Ministral-3-3B, cuando la salida tenía una bandera {@code reformulada}, el
   * modelo devolvía un texto genuinamente distinto de la pregunta con la bandera en {@code false}
   * en la misma respuesta -- un modelo chico con salida estructurada no siempre mantiene ambos
   * campos consistentes. Por eso la salida ya no lleva bandera y se filtra acá.
   */
  static List<String> depurar(String pregunta, List<String> candidatas) {
    List<String> vistas = new ArrayList<>();
    List<String> depuradas = new ArrayList<>();
    String original = pregunta.strip().toLowerCase();
    for (String candidata : candidatas) {
      if (candidata == null || candidata.isBlank()) {
        continue;
      }
      String limpia = candidata.strip();
      String clave = limpia.toLowerCase();
      if (clave.equals(original) || vistas.contains(clave)) {
        continue;
      }
      vistas.add(clave);
      depuradas.add(limpia);
      if (depuradas.size() == MAX_ALTERNATIVAS) {
        break;
      }
    }
    return depuradas;
  }
}
