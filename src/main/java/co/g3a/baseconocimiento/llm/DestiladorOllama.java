package co.g3a.baseconocimiento.llm;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Destilador con salida estructurada forzada por esquema JSON, mismo patrón
 * que {@link PlanificadorOllama}: {@code disableThinking} por defecto y una
 * captura amplia de excepciones que cae a un resumen crudo en vez de tumbar la
 * ingesta del hilo completo si Ollama falla o el modelo no valida el esquema.
 */
@Component
class DestiladorOllama implements Destilador {

    private static final Logger log = LoggerFactory.getLogger(DestiladorOllama.class);
    private static final int LONGITUD_RESUMEN_RESPALDO = 280;

    private static final String SISTEMA = """
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

    DestiladorOllama(ChatClient.Builder builder, @Value("${kb.llm.thinking-habilitado:false}") boolean thinkingHabilitado) {
        var opciones = thinkingHabilitado
                ? OllamaChatOptions.builder().enableThinking()
                : OllamaChatOptions.builder().disableThinking();
        this.chatClient = builder.defaultOptions(opciones).build();
    }

    @Override
    public Destilado destilar(String textoDelHilo) {
        try {
            return chatClient.prompt()
                    .system(SISTEMA)
                    .user(textoDelHilo)
                    .call()
                    .entity(Destilado.class, spec -> spec.useProviderStructuredOutput());
        } catch (Exception e) {
            log.warn("Fallo destilando el hilo, se usa un resumen crudo como respaldo: {}", e.toString());
            return new Destilado(resumenCrudo(textoDelHilo), resumenCrudo(textoDelHilo), "", List.of(), List.of());
        }
    }

    private static String resumenCrudo(String texto) {
        return texto.length() > LONGITUD_RESUMEN_RESPALDO
                ? texto.substring(0, LONGITUD_RESUMEN_RESPALDO) + "…"
                : texto;
    }
}
