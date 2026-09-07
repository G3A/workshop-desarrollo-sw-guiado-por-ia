package co.g3a.baseconocimiento.llm;

import co.g3a.baseconocimiento.compartido.Dominio.IdiomaRespuesta;

/**
 * La frase del prompt de sistema que fija el idioma de la respuesta, compartida por los dos
 * sintetizadores para que ambos digan exactamente lo mismo.
 */
final class InstruccionIdioma {

  private InstruccionIdioma() {}

  static String para(IdiomaRespuesta idioma) {
    return switch (idioma) {
      case ESPANOL -> "Responde en español latinoamericano neutro.";
      case ORIGINAL_DEL_CORPUS ->
          "Responde en el idioma en que estan escritas las fuentes del contexto (por ejemplo, "
              + "en ingles si el contexto esta en ingles), aunque la pregunta llegue en otro "
              + "idioma. Si el contexto mezcla idiomas, usa el que predomina.";
    };
  }
}
