package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Un {@code JsonMapper} propio de este módulo, igual que el de {@code ingesta}: es deliberado que
 * cada módulo tenga el suyo en vez de compartir uno desde {@code compartido} — es tan chico que la
 * indirección no vale lo que cuesta en acoplamiento entre módulos.
 */
final class Json {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private Json() {}

  static String escribir(Object valor) {
    return MAPPER.writeValueAsString(valor);
  }

  /**
   * Deserializa via array en vez de un tipo generico -- mas simple y portable entre versiones de
   * Jackson.
   */
  static List<Cita> leerCitas(String texto) {
    return Arrays.asList(MAPPER.readValue(texto, Cita[].class));
  }
}
