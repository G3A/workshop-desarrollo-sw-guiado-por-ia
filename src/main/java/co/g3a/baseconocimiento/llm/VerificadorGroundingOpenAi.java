package co.g3a.baseconocimiento.llm;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Clasificación binaria con salida estructurada forzada, igual que
 * {@link PlanificadorOpenAi} — no redacción libre, por eso es más confiable
 * que pedirle al {@link Sintetizador} que se autocensure sobre la marcha.
 */
@Component
class VerificadorGroundingOpenAi implements VerificadorGrounding {

    private static final Logger log = LoggerFactory.getLogger(VerificadorGroundingOpenAi.class);

    private static final String SISTEMA = """
            Evaluas si un CONTEXTO recuperado de una base de conocimiento alcanza para
            responder una PREGUNTA puntual. No respondes la pregunta: solo emites un
            veredicto sobre si el contexto la responde de verdad.

            Responde false si el contexto trata un tema distinto al que pregunta la
            persona, aunque comparta palabras sueltas o el mismo tono instructivo. Por
            ejemplo: si el contexto explica como desplegar o correr ESTE proyecto (hecho
            en Java) y la pregunta es sobre el lenguaje Java en si mismo, eso es false --
            compartir la palabra "Java" no es responder la pregunta.

            Presta atencion especial a esta otra forma del mismo problema: una PREGUNTA
            generica sobre una herramienta o tecnologia (p. ej. "como se configura
            Docker?", "que es una GPU?", "que es Ollama?", sin mencionar este proyecto,
            "el servicio" o "la app") contestada con un CONTEXTO que solo explica como
            configurar o usar esa herramienta PARA ESTE PROYECTO puntual (p. ej. los pasos
            de `docker compose up` de este repo). Esa combinacion tambien es false: el
            contexto no responde la pregunta general sobre la herramienta, solo un caso
            particular de uso que la pregunta no pidio. Si en cambio la pregunta ya deja
            claro que se refiere a este proyecto/servicio/app ("como se despliega el
            servicio?", "que GPU necesita este proyecto?"), el mismo contexto si responde:
            true.

            Excepcion puntual a lo anterior: si la PREGUNTA es sobre un archivo o comando
            que solo existe porque este proyecto lo definio (".env.example", "make
            ingest") -- no una tecnologia externa como Java o Docker -- no hay alcance
            genérico con el que confundirse. Ahi, si el CONTEXTO muestra literalmente su
            uso (aunque sea solo el comando puntual, sin una explicacion conceptual
            aparte), eso ya responde la pregunta: true.

            Responde true solo si el contexto de verdad contiene la informacion que la
            pregunta pide, tal como fue formulada -- ni mas especifica ni mas general de
            lo que se pregunto.
            """;

    private final ChatClient chatClient;

    VerificadorGroundingOpenAi(@Qualifier("chatClientBuilderOpenAi") ChatClient.Builder builder) {
        // temperature(0.0): un veredicto sobre si arriesgar una alucinacion no debe
        // variar entre corridas identicas. Se verifico en vivo (con gemma3:4b, antes
        // de ADR-0009) que con 0.2 la MISMA pregunta contra el MISMO contexto daba
        // true en 1 de 3 intentos y false en los otros dos -- ver ADR-0008.
        //
        // extraBody(repeat_penalty): ver PlanificadorOpenAi -- mitiga un modo de
        // falla de repeticion medido en la sesion 6 de la investigacion (ADR-0009).
        var opciones = OpenAiChatOptions.builder()
                .temperature(0.0)
                .extraBody(Map.of("repeat_penalty", 1.1));
        this.chatClient = builder.defaultOptions(opciones).build();
    }

    @Override
    public Veredicto verificar(String pregunta, String contexto) {
        String usuario = "PREGUNTA: %s\n\nCONTEXTO:\n%s".formatted(pregunta, contexto);
        try {
            return chatClient.prompt()
                    .system(SISTEMA)
                    .user(usuario)
                    .call()
                    .entity(Veredicto.class, spec -> spec.useProviderStructuredOutput());
        } catch (Exception e) {
            // A diferencia del planificador (que se cae a search_unified), aca el
            // respaldo ante una falla de llama-server es rechazar: este verificador es
            // la ultima defensa contra una respuesta sin respaldo real, y arriesgar una
            // alucinacion es peor que negarse cuando no se pudo verificar.
            log.warn("Fallo al verificar grounding, se rechaza por precaucion: {}", e.toString());
            return new Veredicto(false);
        }
    }
}
