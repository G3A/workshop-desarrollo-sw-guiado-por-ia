package co.g3a.baseconocimiento.llm;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * Síntesis en streaming. El prompt de sistema es la única defensa contra la
 * alucinación: responder SOLO con lo que trae el contexto, citar cada
 * afirmación con {@code [n]}, y señalar una contradicción en vez de elegir un
 * lado en silencio cuando dos fuentes no coinciden.
 *
 * <p>{@code extraBody(repeat_penalty)}: sin este parámetro, la sesión 6 de la
 * investigación (ADR-0009) midió que este mismo binario (Bonsai 8B via
 * llama-server) repetía la respuesta completa dos veces con el sampling por
 * defecto — un modo de falla que no aparecía con {@code gemma3:4b}/Ollama.
 * No es parte de la API oficial de OpenAI, por eso va en {@code extraBody}.
 * Ya corregido eso, la citacion todavia midio peor que en las pruebas
 * aisladas de la sesion 5 (fragmento irrelevante citado, marcador antes de
 * la afirmacion en vez de despues) — sigue pendiente mas ajuste y
 * re-validacion, no es un problema resuelto del todo.
 *
 * <p>Arma su propio {@link ChatClient.Builder} a partir de {@link OpenAiChatModel}
 * en vez de un {@code ChatClient.Builder} inyectado — ver el comentario de
 * {@link PlanificadorOpenAi} sobre por que compartir un builder-bean entre
 * componentes de este paquete pisaba el {@code maxTokens} de unos con el de
 * otros.
 */
@Component
class SintetizadorOpenAi implements Sintetizador {

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

    SintetizadorOpenAi(OpenAiChatModel modelo) {
        // maxTokens(512): a diferencia de Planificador/VerificadorGrounding (salida
        // corta y acotada), una sintesis real puede necesitar varios pasos y varias
        // citas -- 512 da margen de sobra para eso. Es una red de seguridad contra
        // un bucle de generacion sin fin (ver ADR-0009, hallazgo de repeticion de
        // la sesion 6), no un limite pensado para recortar respuestas normales.
        var opciones = OpenAiChatOptions.builder()
                .extraBody(Map.of("repeat_penalty", 1.1))
                .maxTokens(512);
        this.chatClient = ChatClient.builder(modelo).defaultSystem(SISTEMA).defaultOptions(opciones).build();
    }

    @Override
    public Flux<String> sintetizar(String pregunta, String contexto) {
        String usuario = "Pregunta: %s\n\nContexto:\n%s".formatted(pregunta, contexto);
        return chatClient.prompt().user(usuario).stream().content();
    }
}
