package co.g3a.baseconocimiento.llm;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * Alternativa a {@link SintetizadorOpenAi}: en vez de prosa libre con
 * marcadores {@code [n]} incrustados a mano, fuerza la síntesis como salida
 * JSON estructurada (una lista de afirmaciones, cada una con su fuente),
 * igual que ya hacen {@link PlanificadorOpenAi} y {@link VerificadorGroundingOpenAi}
 * — y ese mismo patrón sí les funcionó a modelos que, en texto libre, pegaban
 * el fragmento crudo en vez de redactar (`granite4.1:3b`, `phi4-mini:3.8b`,
 * `qwen2.5:3b`, `nemotron-mini:4b`, `SmolLM3-3B` — ver sesiones 2-23 de
 * docs/investigacion-vram-y-modelo-llm.md). Es la vía que la sesión 3 de esa
 * investigación había dejado anotada como no explorada.
 *
 * <p><b>Desactivado por defecto</b> ({@code kb.llm.sintesis-estructurada.habilitada:false}):
 * {@link SintetizadorOpenAi} sigue siendo el {@link Sintetizador} real de
 * producción sin ningún cambio de comportamiento. Activar esta clase es
 * responsabilidad explícita de quien la prueba
 * ({@code KB_LLM_SINTESIS_ESTRUCTURADA_HABILITADA=true}), no un default nuevo.
 *
 * <p><b>Trade-off sin resolver, documentado a propósito</b>: {@link Sintetizador}
 * devuelve {@code Flux<String>} porque {@link SintetizadorOpenAi} transmite
 * token a token para la UI en streaming (ver {@code Orquestador#ejecutarEnStreaming}).
 * La salida JSON forzada no se puede transmitir así de forma incremental sin
 * parsear JSON parcial — esta implementación arma la respuesta completa en una
 * sola llamada bloqueante y la devuelve como un {@link Flux} de un solo
 * elemento. Sigue cumpliendo el contrato de la interfaz sin tocar
 * {@code Orquestador} ni el adaptador SSE, pero la UI verá la respuesta
 * aparecer de una sola vez en vez de palabra por palabra. Nadie midió todavía
 * si esto es aceptable para el caso de uso real — parte de por qué esta clase
 * arranca apagada.
 *
 * <p>Cada {@code Afirmacion} lleva UNA sola fuente ({@code int}, no una
 * lista): a diferencia del prompt de texto libre, que le pedía al modelo "un
 * solo marcador por afirmación" y aun así `gemma3:4b` agrupaba varios
 * (sobre-citación, hallazgo 6 de la investigación), acá el esquema lo impide
 * por construcción — si una afirmación necesita dos fuentes, el modelo tiene
 * que partirla en dos afirmaciones, no hay campo para poner dos números.
 */
@Component
@ConditionalOnProperty(prefix = "kb.llm.sintesis-estructurada", name = "habilitada", havingValue = "true")
class SintetizadorEstructuradoOpenAi implements Sintetizador {

    private static final String SISTEMA = """
            Eres el sintetizador de una base de conocimiento interna. Respondes SOLO con lo
            que aparece en el contexto que se te da a continuacion, nunca con conocimiento
            propio. Responde en español latinoamericano neutro.

            Tu respuesta es una lista de afirmaciones. Cada afirmacion es un objeto con dos
            campos: "texto" (una oracion o frase puntual, en prosa propia -- nunca una copia
            literal del fragmento fuente) y "fuente" (el numero [n] del UNICO fragmento del
            contexto que respalda esa afirmacion puntual). Divide la respuesta en tantas
            afirmaciones como haga falta para cubrir la pregunta; cada una cita una sola
            fuente -- si una idea depende de dos fragmentos distintos, parte esa idea en dos
            afirmaciones separadas, una por fuente.

            No copies el fragmento tal cual: redacta la afirmacion con tus propias palabras,
            manteniendo el significado exacto. Si dos fuentes del contexto se contradicen
            entre si, agrega una afirmacion que señale la contradiccion explicitamente, citando
            la fuente que la muestra.
            """;

    /**
     * @param fuente el número [n] del fragmento del contexto que respalda a
     *               {@code texto}, tal como aparece en el prompt (1-indexado)
     */
    record Afirmacion(String texto, int fuente) {
    }

    record SintesisEstructurada(List<Afirmacion> afirmaciones) {
    }

    private final ChatClient chatClient;

    SintetizadorEstructuradoOpenAi(OpenAiChatModel modelo) {
        // maxTokens(4000), no 512 como SintetizadorOpenAi: el overhead de nombres
        // de campo y sintaxis JSON (comillas, llaves, comas) por afirmacion pesa
        // mas que el texto libre equivalente. Subido dos veces en vivo (smoke10):
        // 700->1500 tras ver a qwen2.5:3b cortar a mitad de la 9a afirmacion, y
        // 1500->4000 tras ver a SmolLM3-3B cortar a mitad de la 23a (indice 22) --
        // Jackson tira UnexpectedEndOfInputException al no poder cerrar el JSON en
        // ambos casos. La verbosidad de "cuantas afirmaciones separa el modelo"
        // varia mucho por modelo/pregunta, sin relacion clara con la longitud real
        // de la respuesta -- 4000 sigue siendo un margen de partida, no un numero
        // calibrado, y Ministral tambien trunco 2/10 preguntas con 1500 (sin
        // remedir todavia con 4000).
        //
        // extraBody(reasoning_effort=none): ver PlanificadorOpenAi -- mismo
        // riesgo si se usa con un modelo con "thinking" nativo. NO lleva
        // repeat_penalty/repeat_last_n como SintetizadorOpenAi: ese ajuste
        // respondia a un modo de falla de repeticion medido en texto libre
        // (sesion 6, ADR-0009) que no tiene evidencia propia todavia bajo
        // salida JSON forzada -- la gramatica de la salida estructurada ya
        // restringe los tokens validos en cada posicion, lo que reduce por
        // diseño el espacio para repetir texto libre sin sentido.
        var opciones = OpenAiChatOptions.builder()
                .extraBody(Map.of("reasoning_effort", "none"))
                .maxTokens(4000);
        this.chatClient = ChatClient.builder(modelo).defaultSystem(SISTEMA).defaultOptions(opciones).build();
    }

    @Override
    public Flux<String> sintetizar(String pregunta, String contexto) {
        // Flux.defer: la llamada bloqueante a entity() tiene que ocurrir DENTRO
        // del Flux, no antes de construirlo. Orquestador.ejecutarEnStreaming()
        // libera el cupo de consultas concurrentes en el doFinally() de este
        // Flux -- si entity() lanzara una excepcion fuera de el (por ejemplo,
        // JSON truncado por quedarse sin maxTokens, visto en vivo con
        // qwen2.5:3b), esa excepcion escaparia de forma sincronica antes de que
        // el Flux exista, el doFinally nunca se engancharia, y el cupo quedaria
        // agotado para siempre hasta reiniciar kb-api.
        return Flux.defer(() -> {
            String usuario = "Pregunta: %s\n\nContexto:\n%s".formatted(pregunta, contexto);
            SintesisEstructurada resultado = chatClient.prompt()
                    .user(usuario)
                    .call()
                    .entity(SintesisEstructurada.class, spec -> spec.useProviderStructuredOutput());

            if (resultado == null || resultado.afirmaciones().isEmpty()) {
                return Flux.just("");
            }
            String texto = resultado.afirmaciones().stream()
                    .map(a -> "%s [%d]".formatted(a.texto().strip(), a.fuente()))
                    .collect(Collectors.joining(" "));
            return Flux.just(texto);
        });
    }
}
