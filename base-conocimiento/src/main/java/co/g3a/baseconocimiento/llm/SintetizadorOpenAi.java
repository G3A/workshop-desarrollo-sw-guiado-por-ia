package co.g3a.baseconocimiento.llm;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * Síntesis en streaming. El prompt de sistema es la única defensa contra la
 * alucinación: responder SOLO con lo que trae el contexto, citar cada
 * afirmación con {@code [n]}, y señalar una contradicción en vez de elegir un
 * lado en silencio cuando dos fuentes no coinciden.
 *
 * <p>Es el {@link Sintetizador} activo por defecto
 * ({@code kb.llm.sintesis-estructurada.habilitada:false}). La alternativa,
 * {@link SintetizadorEstructuradoOpenAi}, fuerza salida JSON en vez de prosa
 * libre — ver su javadoc para cuándo conviene probarla.
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
@ConditionalOnProperty(
        prefix = "kb.llm.sintesis-estructurada", name = "habilitada", havingValue = "false", matchIfMissing = true)
class SintetizadorOpenAi implements Sintetizador {

    private static final String SISTEMA = """
            Eres el sintetizador de una base de conocimiento interna. Respondes SOLO con lo
            que aparece en el contexto que se te da a continuacion, nunca con conocimiento
            propio. Si el contexto no alcanza para responder la pregunta, dilo explicitamente
            en vez de inventar. Si dos fuentes del contexto se contradicen entre si, señala la
            contradiccion en la respuesta en vez de elegir una en silencio. Responde en español
            latinoamericano neutro.

            Cada afirmacion debe llevar el marcador [n] de la fuente numerada en el contexto de
            la que sale, pegado al final de esa afirmacion puntual -- nunca antes de ella, y
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
        //
        // repeat_last_n(4096): medido en vivo (sesion 12/13 de la investigacion,
        // ADR-0009) que el default de llama-server (64 tokens) deja el prompt de
        // sistema completo fuera de la ventana de repeat_penalty en cuanto el
        // contexto supera unos pocos fragmentos -- Bonsai terminaba copiando texto
        // literal del propio prompt de sistema (incluida la palabra en mayusculas
        // que marcaba un ejemplo de citacion) como si fuera parte de la respuesta.
        // Un valor grande le pide a llama-server que penalice repeticion contra
        // el CONTEXTO COMPLETO, no solo los ultimos 64 tokens -- 4096 empata con
        // BONSAI_CTX_SIZE (compose.bonsai.yml). El semantico de llama.cpp para
        // esto es -1 ("todo el contexto"), pero se descarto: medido en vivo
        // (sesion 14, comparando contra Ministral) que el build oficial de
        // llama.cpp (ghcr.io/ggml-org/llama.cpp, el que sirve a cualquier
        // candidato sin el fork de PrismML) rechaza valores negativos con 400
        // ("Value must be between 0 <= value <= 2147483647") -- el fork de
        // PrismML que sirve a Bonsai hoy es mas permisivo y sí acepta -1, pero
        // depender de esa laxitud ataba el prompt a un solo backend. Un numero
        // positivo que ya cubre el contexto completo evita el problema en los
        // dos backends sin cambiar el efecto practico. No es parte de la API
        // oficial de OpenAI, por eso va en extraBody como repeat_penalty.
        //
        // reasoning_effort(none): ver PlanificadorOpenAi -- necesario para modelos
        // con "thinking" nativo (qwen3.5:4b). A diferencia de Planificador/
        // VerificadorGrounding (salida JSON forzada, donde el pensamiento sin
        // apagar hace fallar la llamada por completo), aca con texto libre el
        // sintoma medido fue mas sutil: la sesion 18 (hallazgo 77) midio una
        // respuesta truncada a mitad de frase y sin ninguna cita [n] en la
        // pregunta de control, pese a maxTokens(512) -- el presupuesto se gasta
        // pensando antes de llegar a escribir la respuesta real.
        //
        // presencePenalty(0.1): repeat_last_n(-1) solo bajo la fuga, no la
        // eliminaba del todo. Combinado con esta penalidad estandar de OpenAI (es
        // parte del contrato, no necesita extraBody) si desaparecio en las
        // pruebas -- medido en vivo que 0.3 ya quitaba las citas [n] casi por
        // completo, y 0.6+ empujaba al modelo a divagar sobre los fragmentos
        // irrelevantes del contexto en vez de ignorarlos. 0.1 fue el valor mas
        // bajo que ya alcanzaba a suprimir la fuga sin perder las citas.
        var opciones = OpenAiChatOptions.builder()
                .extraBody(Map.of("repeat_penalty", 1.1, "repeat_last_n", 4096, "reasoning_effort", "none"))
                .presencePenalty(0.1)
                .maxTokens(512);
        this.chatClient = ChatClient.builder(modelo).defaultSystem(SISTEMA).defaultOptions(opciones).build();
    }

    @Override
    public Flux<String> sintetizar(String pregunta, String contexto) {
        String usuario = "Pregunta: %s\n\nContexto:\n%s".formatted(pregunta, contexto);
        return chatClient.prompt().user(usuario).stream().content();
    }
}
