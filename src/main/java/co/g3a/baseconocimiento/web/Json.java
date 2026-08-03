package co.g3a.baseconocimiento.web;

import tools.jackson.databind.json.JsonMapper;

/**
 * Un {@code JsonMapper} propio de este módulo, igual que el de {@code ingesta},
 * {@code orquestacion} y {@code teams}: es deliberado que cada módulo tenga el
 * suyo en vez de compartir uno desde {@code compartido} — es tan chico que la
 * indirección no vale lo que cuesta en acoplamiento entre módulos.
 */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    static String escribir(Object valor) {
        return MAPPER.writeValueAsString(valor);
    }
}
