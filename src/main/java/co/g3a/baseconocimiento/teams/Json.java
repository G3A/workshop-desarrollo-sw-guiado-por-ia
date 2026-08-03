package co.g3a.baseconocimiento.teams;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Un {@code JsonMapper} propio de este módulo, igual que {@code ingesta} y
 * {@code orquestacion}: cada módulo con el suyo en vez de compartir uno desde
 * {@code compartido}.
 */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    static String escribir(Object valor) {
        return MAPPER.writeValueAsString(valor);
    }

    static JsonNode leer(String texto) {
        return MAPPER.readTree(texto);
    }
}
