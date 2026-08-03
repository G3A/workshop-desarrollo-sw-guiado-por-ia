package co.g3a.baseconocimiento.llm;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Planner con salida estructurada forzada por esquema JSON, vía
 * {@code ChatClient.entity(..., spec -> spec.useProviderStructuredOutput())}:
 * Ollama valida la forma de la respuesta antes de devolverla, así que no hace
 * falta parsear texto libre ni tolerar un JSON a medias.
 *
 * <p>{@code kb.llm.thinking-habilitado=false} por defecto no es cosmético:
 * {@code qwen3} —el modelo original de este proyecto, ver el hallazgo de F4 en
 * el plan— trae el modo "thinking" activado por defecto en Ollama, y para una
 * decisión tan simple como "cuáles de seis herramientas uso" eso agregaba
 * minutos de razonamiento interno irrelevante antes de la respuesta —
 * verificado en vivo contra Ollama real: un prompt trivial tardó 3m28s con
 * thinking encendido, contra segundos con el flag apagado. El modelo por
 * defecto pasó a {@code gemma3:4b} (que no tiene ese modo), pero la propiedad
 * queda: sirve igual si alguien vuelve a apuntar {@code kb.llm.modelo} a un
 * modelo con thinking.
 */
@Component
class PlanificadorOllama implements Planificador {

    private static final Logger log = LoggerFactory.getLogger(PlanificadorOllama.class);

    private final ChatClient chatClient;

    PlanificadorOllama(
            ChatClient.Builder builder,
            @Value("${kb.llm.thinking-habilitado:false}") boolean thinkingHabilitado) {
        // ChatClient.Builder.defaultOptions(...) pide el Builder de opciones,
        // no una instancia ya construida -- distinto de Spring AI 1.x.
        var opciones = thinkingHabilitado
                ? OllamaChatOptions.builder().enableThinking()
                : OllamaChatOptions.builder().disableThinking();
        this.chatClient = builder.defaultOptions(opciones).build();
    }

    @Override
    public PlanDeHerramientas planificar(String pregunta, Map<String, String> herramientasDisponibles) {
        String catalogo = herramientasDisponibles.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> "- %s: %s".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));

        String sistema = """
                Eres el planificador de una base de conocimiento interna. Dada una pregunta,
                elige QUE herramientas de busqueda ejecutar, de esta lista:

                %s

                Elige solo las que de verdad ayuden a responder esa pregunta puntual. Si dudas,
                incluye "search_unified": es la busqueda general y casi siempre aporta algo.
                No inventes nombres de herramientas fuera de la lista.

                Cuidado con una confusion frecuente: preguntas sobre requisitos de hardware
                (por ejemplo, que GPU o cuanta memoria hace falta), instalacion, despliegue o
                configuracion casi siempre se responden con la documentacion, no con el codigo
                fuente -- usa search_docs/search_unified para esas, no search_code. search_code
                es solo para "como esta implementado X" (una funcion, una constante, un mensaje
                de error puntual), no para "que necesito para correr X".
                """.formatted(catalogo);

        try {
            PlanDeHerramientas plan = chatClient.prompt()
                    .system(sistema)
                    .user(pregunta)
                    .call()
                    .entity(PlanDeHerramientas.class, spec -> spec.useProviderStructuredOutput());

            List<String> validas = plan.herramientas().stream()
                    .filter(herramientasDisponibles::containsKey)
                    .toList();
            return new PlanDeHerramientas(validas, plan.razon());
        } catch (Exception e) {
            // El planner nunca debe tumbar la pregunta: si Ollama no responde o
            // devuelve algo que no valida contra el esquema, se cae a la busqueda
            // unificada en vez de fallar toda la consulta.
            log.warn("Fallo al planificar, se usa search_unified como respaldo: {}", e.toString());
            return new PlanDeHerramientas(List.of("search_unified"), "respaldo: el planner fallo");
        }
    }
}
