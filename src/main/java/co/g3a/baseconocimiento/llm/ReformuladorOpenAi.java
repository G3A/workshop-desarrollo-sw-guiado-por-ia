package co.g3a.baseconocimiento.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * {@code maxTokens(120)}: la consulta reformulada debe ser una frase de
 * búsqueda corta, no una respuesta -- mismo espíritu que el tope de
 * {@link PlanificadorOpenAi} para el campo {@code razon}.
 *
 * <p>Arma su propio {@link ChatClient.Builder} en vez de un bean compartido,
 * por la misma razón documentada en {@link PlanificadorOpenAi}: un
 * {@code ChatClient.Builder} inyectado es mutable y compartirlo entre
 * componentes con distinto {@code maxTokens} termina con todos usando el del
 * último bean que Spring inicializa.
 */
@Component
class ReformuladorOpenAi implements Reformulador {

    private static final Logger log = LoggerFactory.getLogger(ReformuladorOpenAi.class);

    private static final String SISTEMA = """
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
        var opciones = OpenAiChatOptions.builder().maxTokens(120);
        this.chatClient = ChatClient.builder(modelo).defaultOptions(opciones).build();
    }

    @Override
    public Reformulacion reformular(String pregunta) {
        try {
            Reformulacion resultado = chatClient.prompt()
                    .system(SISTEMA)
                    .user(pregunta)
                    .call()
                    .entity(Reformulacion.class, spec -> spec.useProviderStructuredOutput());

            if (resultado == null || resultado.textoBusqueda() == null || resultado.textoBusqueda().isBlank()) {
                return new Reformulacion(pregunta, false);
            }
            if (!resultado.reformulada() || resultado.textoBusqueda().equals(pregunta)) {
                return new Reformulacion(pregunta, false);
            }
            return resultado;
        } catch (Exception e) {
            // Igual que el planner: la reformulacion nunca debe tumbar la pregunta. Si
            // llama-server no responde o el JSON no valida, se busca con el texto
            // original -- ni mejor ni peor que el comportamiento previo a este componente.
            log.warn("Fallo al reformular la consulta, se usa la pregunta original: {}", e.toString());
            return new Reformulacion(pregunta, false);
        }
    }
}
