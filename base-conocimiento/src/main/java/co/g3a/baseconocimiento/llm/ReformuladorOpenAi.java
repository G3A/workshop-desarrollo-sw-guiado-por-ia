package co.g3a.baseconocimiento.llm;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * {@code maxTokens(120)}: la consulta reformulada debe ser una frase de búsqueda corta, no una
 * respuesta -- mismo espíritu que el tope de {@link PlanificadorOpenAi} para el campo {@code
 * razon}.
 *
 * <p>{@code extraBody(reasoning_effort=none)}: ver {@link PlanificadorOpenAi} -- este componente se
 * sumó en la sesión 17 de la investigación (ver docs/investigacion-vram-y-modelo-llm.md), después
 * de que la sesión 18 documentara el fix, así que había quedado sin el ajuste. Medido en vivo con
 * la sesión 20 (perfil {@code qwen3.5:4b}): sin esto, el mismo síntoma de "thinking" se comía el
 * {@code maxTokens(120)} y el log mostraba "Fallo al reformular la consulta, se usa la pregunta
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

  private static final String SISTEMA =
      """
            Eres un asistente de reformulacion de consultas para una busqueda hibrida
            (texto + vectorial) contra una base de conocimiento tecnica. La tarea NO es
            responder la pregunta -- es proponer, solo si hace falta, una version alternativa
            de la CONSULTA DE BUSQUEDA que use el vocabulario y el idioma mas probable de la
            documentacion fuente, para maximizar las coincidencias lexicas y semanticas.

            Reformula SOLO si la pregunta parece usar terminos coloquiales, abreviados o
            informales que probablemente no aparecen tal cual en documentacion tecnica formal
            (ejemplo: "autoboxing" en vez del termino formal "boxing conversion"; jerga de la
            industria en vez del nombre oficial de una especificacion). La documentacion
            tecnica de referencia (especificaciones de lenguajes, estandares, RFCs) suele estar
            escrita en ingles formal, incluso cuando la pregunta llega en español -- si eso
            parece el caso, propone la consulta reformulada en el idioma y la terminologia que
            mas probablemente aparezcan en el texto fuente.

            Si la pregunta ya usa terminologia probablemente formal, o no hay forma de saber si
            existe un termino tecnico mas formal, deja la consulta SIN CAMBIOS y marca
            reformulada=false. No inventes terminologia de la que no haya certeza razonable --
            es preferible no reformular a reformular mal.

            Responde solo con la consulta de busqueda (reformulada o la original sin cambios),
            nunca con una respuesta a la pregunta en si.
            """;

  private final ChatClient chatClient;

  ReformuladorOpenAi(OpenAiChatModel modelo) {
    var opciones =
        OpenAiChatOptions.builder().extraBody(Map.of("reasoning_effort", "none")).maxTokens(120);
    this.chatClient = ChatClient.builder(modelo).defaultOptions(opciones).build();
  }

  @Override
  public Reformulacion reformular(String pregunta) {
    try {
      Reformulacion resultado =
          chatClient
              .prompt()
              .system(SISTEMA)
              .user(pregunta)
              .call()
              .entity(Reformulacion.class, spec -> spec.useProviderStructuredOutput());

      if (resultado == null
          || resultado.textoBusqueda() == null
          || resultado.textoBusqueda().isBlank()) {
        return new Reformulacion(pregunta, false);
      }
      // El campo "reformulada" del propio modelo no es confiable: medido en vivo con
      // Ministral-3-3B, devolvio un textoBusqueda genuinamente distinto de la pregunta
      // ("autoboxing conversion in Java automatic occurrence conditions" para "que es
      // el autoboxing...") pero con reformulada=false en la misma respuesta -- un modelo
      // chico con salida estructurada no siempre mantiene ambos campos consistentes.
      // Comparar el texto en si es la unica senal que no depende de que el modelo
      // "sepa" que reformulo.
      boolean cambio = !resultado.textoBusqueda().strip().equalsIgnoreCase(pregunta.strip());
      if (!cambio) {
        return new Reformulacion(pregunta, false);
      }
      return new Reformulacion(resultado.textoBusqueda(), true);
    } catch (Exception e) {
      // Igual que el planner: la reformulacion nunca debe tumbar la pregunta. Si
      // llama-server no responde o el JSON no valida, se busca con el texto
      // original -- ni mejor ni peor que el comportamiento previo a este componente.
      log.warn("Fallo al reformular la consulta, se usa la pregunta original: {}", e.toString());
      return new Reformulacion(pregunta, false);
    }
  }
}
