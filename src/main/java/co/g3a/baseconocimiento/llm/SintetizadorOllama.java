package co.g3a.baseconocimiento.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * Síntesis en streaming. El prompt de sistema es la única defensa contra la
 * alucinación: responder SOLO con lo que trae el contexto, citar cada
 * afirmación con {@code [n]}, y señalar una contradicción en vez de elegir un
 * lado en silencio cuando dos fuentes no coinciden.
 *
 * <p>{@code kb.llm.thinking-habilitado=false} por defecto, igual que en
 * {@link PlanificadorOllama}. Esta propiedad y {@code disableThinking()} nacieron
 * por {@code qwen3:4b} —el modelo original de este proyecto—: con "thinking"
 * encendido, Ollama anteponía varios minutos de razonamiento interno a cada
 * respuesta en este equipo, y aun con thinking apagado el modelo seguía
 * narrando su proceso en prosa dentro de la respuesta misma ("Okay, let's
 * tackle this query..."). Dos intentos de arreglarlo por prompt, verificados
 * en vivo, no lo resolvieron — uno de ellos (un ejemplo few-shot) lo empeoró:
 * el modelo trató el ejemplo como parte del problema y entró en un bucle de
 * auto-cuestionamiento de varios minutos sin llegar a una respuesta.
 *
 * <p>Por eso el modelo por defecto pasó a {@code gemma3:4b} (ver hallazgos de
 * F4 en el plan): mismo tamaño y presupuesto de VRAM que {@code qwen3:4b}, sin
 * modo thinking. La propiedad {@code thinking-habilitado} queda para si
 * alguien vuelve a apuntar {@code kb.llm.modelo} a un modelo que sí lo tenga.
 */
@Component
class SintetizadorOllama implements Sintetizador {

    private static final String SISTEMA = """
            Eres el sintetizador de una base de conocimiento interna. Respondes SOLO con lo
            que aparece en el contexto que se te da a continuacion, nunca con conocimiento
            propio. Si el contexto no alcanza para responder la pregunta, dilo explicitamente
            en vez de inventar. Si dos fuentes del contexto se contradicen entre si, señala la
            contradiccion en la respuesta en vez de elegir una en silencio. Responde en español
            latinoamericano neutro.

            Cada afirmacion debe llevar el marcador [n] de la fuente numerada en el contexto de
            la que sale, PEGADO al final de esa afirmacion puntual -- nunca antes de ella, y
            nunca varios marcadores sueltos agrupados al final del texto sin decir que frase
            respalda cada uno. Ejemplo correcto: "Se necesita Docker Desktop iniciado [2]."
            Ejemplo incorrecto: "Para configurar Docker, [2] se necesita..." (la cita antes de
            la afirmacion) o dejar "[1], [3]" sueltos al cierre de la respuesta.

            Ve directo a la respuesta. NO narres tu razonamiento ("primero voy a...",
            "el usuario esta preguntando...", "veamos el contexto..."): eso no es la
            respuesta, es ruido que el usuario tiene que leer igual. La primera palabra
            que escribas debe ser parte de la respuesta misma.
            """;

    private final ChatClient chatClient;

    SintetizadorOllama(
            ChatClient.Builder builder,
            @Value("${kb.llm.thinking-habilitado:false}") boolean thinkingHabilitado) {
        // ChatClient.Builder.defaultOptions(...) pide el Builder de opciones,
        // no una instancia ya construida -- distinto de Spring AI 1.x.
        var opciones = thinkingHabilitado
                ? OllamaChatOptions.builder().enableThinking()
                : OllamaChatOptions.builder().disableThinking();
        this.chatClient = builder.defaultSystem(SISTEMA).defaultOptions(opciones).build();
    }

    @Override
    public Flux<String> sintetizar(String pregunta, String contexto) {
        String usuario = "Pregunta: %s\n\nContexto:\n%s".formatted(pregunta, contexto);
        return chatClient.prompt().user(usuario).stream().content();
    }
}
