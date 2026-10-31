package co.g3a.baseconocimiento.llm;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

/**
 * Destilador con salida estructurada forzada por esquema JSON y una captura amplia de excepciones
 * que cae a un resumen crudo en vez de tumbar la ingesta del hilo completo si {@code llama-server}
 * falla o el modelo no valida el esquema.
 *
 * <p>Hasta ADR-0017 era el unico componente de {@code llm/} que hablaba con Ollama directo, con su
 * propio modelo ({@code KB_TEAMS_LLM_MODELO}) y un {@code disableThinking()}. Sin Ollama usa el
 * mismo {@link OpenAiChatModel} que el resto del paquete, y por lo tanto el mismo LLM: Granite no
 * tiene modo "thinking" que apagar.
 *
 * <p>Arma su propio {@link ChatClient.Builder} a partir del modelo en vez de pedir uno inyectado,
 * por el mismo motivo que {@link PlanificadorOpenAi}: compartir un builder como bean (singleton,
 * mutable) entre componentes hace que las opciones de uno pisen las de otro.
 */
@Component
class DestiladorOpenAi implements Destilador {

  private static final Logger log = LoggerFactory.getLogger(DestiladorOpenAi.class);
  private static final int LONGITUD_RESUMEN_RESPALDO = 280;

  private static final String SISTEMA =
      """
            Eres el destilador de una base de conocimiento interna. Recibis un hilo de conversacion
            de Teams (una pregunta y sus respuestas, cada una con el nombre de quien la escribio) y
            lo convertis en una forma estructurada para busqueda:

            - searchableQuestion: una pregunta buscable que resuma de que trata el hilo.
            - summary: un resumen breve del hilo completo.
            - resolution: como se resolvio, o una cadena vacia si el hilo no llego a una resolucion.
            - systemsMentioned: sistemas o herramientas mencionados.
            - codeReferences: rutas de archivo, nombres de clases o funciones mencionados, si los hay.
            """;

  private final ChatClient chatClient;

  DestiladorOpenAi(OpenAiChatModel modelo) {
    this.chatClient = ChatClient.builder(modelo).build();
  }

  @Override
  public Destilado destilar(String textoDelHilo) {
    try {
      return chatClient
          .prompt()
          .system(SISTEMA)
          .user(textoDelHilo)
          .call()
          .entity(Destilado.class, spec -> spec.useProviderStructuredOutput());
    } catch (Exception e) {
      log.warn("Fallo destilando el hilo, se usa un resumen crudo como respaldo: {}", e.toString());
      return new Destilado(
          resumenCrudo(textoDelHilo), resumenCrudo(textoDelHilo), "", List.of(), List.of());
    }
  }

  private static String resumenCrudo(String texto) {
    return texto.length() > LONGITUD_RESUMEN_RESPALDO
        ? texto.substring(0, LONGITUD_RESUMEN_RESPALDO) + "…"
        : texto;
  }
}
