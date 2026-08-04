package co.g3a.baseconocimiento.llm;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * Planner con salida estructurada forzada por esquema JSON, vía
 * {@code ChatClient.entity(..., spec -> spec.useProviderStructuredOutput())}:
 * el proveedor (OpenAI, aquí contra llama-server sirviendo Bonsai 8B — ver
 * ADR-0009) valida la forma de la respuesta antes de devolverla, así que no
 * hace falta parsear texto libre ni tolerar un JSON a medias.
 *
 * <p>Sin modo "thinking" que apagar: a diferencia de {@code qwen3} —el modelo
 * original de este proyecto— Bonsai no tiene esa capacidad nativa, así que
 * esta clase ya no necesita la propiedad {@code kb.llm.thinking-habilitado}
 * ni el {@code enableThinking()}/{@code disableThinking()} de
 * {@code OllamaChatOptions} que usaba cuando se llamaba
 * {@code PlanificadorOllama}.
 *
 * <p>{@code extraBody(repeat_penalty)}: sin este parámetro, la sesión 6 de la
 * investigación (ADR-0009) midió que la síntesis con este mismo binario podía
 * repetir la respuesta completa dos veces con el sampling por defecto de
 * {@code llama-server} — no es parte de la API oficial de OpenAI, por eso va
 * en {@code extraBody} y no en un campo propio de {@link OpenAiChatOptions}.
 *
 * <p>{@code maxTokens(80)}: sin este tope, se midió en vivo que el campo
 * {@code razon} del plan podía salir como una oración completa (~90 de 110
 * tokens generados) en vez de la frase breve que pide el prompt — a
 * ~5-6 tok/s de Bonsai en esta GPU (ver ADR-0009), eso solo son ~20 segundos
 * gastados en texto que nadie necesita.
 *
 * <p>Arma su propio {@link ChatClient.Builder} a partir de {@link OpenAiChatModel}
 * en vez de pedir un {@code ChatClient.Builder} inyectado: compartir un
 * builder como bean (singleton, mutable) entre este componente,
 * {@link VerificadorGroundingOpenAi} y {@link SintetizadorOpenAi} hacia que
 * el {@code maxTokens} de uno pisara el de los otros dos, medido en vivo
 * (los tres terminaban con el mismo tope, el del ultimo bean que Spring
 * inicializaba) antes de corregirlo con este patron.
 */
@Component
class PlanificadorOpenAi implements Planificador {

    private static final Logger log = LoggerFactory.getLogger(PlanificadorOpenAi.class);

    private final ChatClient chatClient;

    PlanificadorOpenAi(OpenAiChatModel modelo) {
        var opciones = OpenAiChatOptions.builder()
                .extraBody(Map.of("repeat_penalty", 1.1))
                .maxTokens(80);
        this.chatClient = ChatClient.builder(modelo).defaultOptions(opciones).build();
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
                de error puntual) EN ESTE repositorio -- no para "que necesito para correr X",
                ni para preguntas de CONCEPTOS del lenguaje (tipos de dato, palabras reservadas,
                sintaxis, reglas del lenguaje). "Cuales son los tipos primitivos en Java" es
                conceptual, no de implementacion: usa search_docs/search_unified, nunca
                search_code. search_code exige que exista un repo real ya indexado -- si dudas
                de si la pregunta apunta al codigo DE ESTE proyecto o a un concepto general del
                lenguaje/framework, es conceptual: usa search_docs/search_unified.

                El campo "razon" es SOLO para trazabilidad interna, nadie la lee como respuesta:
                maximo 6-8 palabras, nunca una oracion completa. Ejemplo correcto: "pregunta de
                despliegue, no de codigo". Ejemplo incorrecto: una explicacion de un parrafo.
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
            if (validas.isEmpty()) {
                // El prompt le pide explicitamente "si dudas, incluye search_unified", pero un
                // modelo debil (Bonsai-8B cuantizado) a veces devuelve una lista vacia igual --
                // medido en vivo. Sin este respaldo, una lista vacia deja al Executor sin nada
                // que correr y la pregunta cae directo al mensaje de "sin informacion", aunque
                // el contenido si este ingerido.
                log.warn("El planner eligio una lista de herramientas vacia, se usa search_unified como respaldo");
                return new PlanDeHerramientas(List.of("search_unified"), "respaldo: lista vacia del planner");
            }
            return new PlanDeHerramientas(validas, plan.razon());
        } catch (Exception e) {
            // El planner nunca debe tumbar la pregunta: si llama-server no responde o
            // devuelve algo que no valida contra el esquema, se cae a la busqueda
            // unificada en vez de fallar toda la consulta.
            log.warn("Fallo al planificar, se usa search_unified como respaldo: {}", e.toString());
            return new PlanDeHerramientas(List.of("search_unified"), "respaldo: el planner fallo");
        }
    }
}
