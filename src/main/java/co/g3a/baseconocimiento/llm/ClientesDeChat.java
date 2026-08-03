package co.g3a.baseconocimiento.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Con Ollama y OpenAI activos a la vez (el Destilador de Teams se quedo en
 * Ollama; el resto de {@code llm/} pasa por OpenAI contra llama-server, ver
 * ADR-0009), Spring ya no puede autoconfigurar un {@link ChatClient.Builder}
 * por defecto: hay dos {@code ChatModel} candidatos y ninguno es "el unico".
 * Estos dos beans reemplazan a ese default, uno por proveedor, para que cada
 * componente pida el que necesita con {@code @Qualifier}.
 */
@Configuration
class ClientesDeChat {

    @Bean
    ChatClient.Builder chatClientBuilderOllama(OllamaChatModel modelo) {
        return ChatClient.builder(modelo);
    }

    @Bean
    ChatClient.Builder chatClientBuilderOpenAi(OpenAiChatModel modelo) {
        return ChatClient.builder(modelo);
    }
}
