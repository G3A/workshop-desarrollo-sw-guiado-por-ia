package co.g3a.baseconocimiento.ingesta;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Un solo {@code JsonMapper} para todo el módulo.
 *
 * <p>OJO: Spring Boot 4 trae Jackson 3 ({@code tools.jackson.databind}), no
 * Jackson 2 ({@code com.fasterxml.jackson.databind}). Los nombres de paquete
 * cambiaron y {@link tools.jackson.core.JacksonException} ahora es una
 * excepción de tiempo de ejecución, no una {@code IOException} — por eso las
 * llamadas de abajo no piden {@code try/catch}.
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

    /** Nunca null: cadena vacía si el campo falta o es JSON {@code null}. */
    static String textoDe(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        return (valor == null || valor.isNull() || valor.isMissingNode()) ? "" : valor.asString();
    }
}
