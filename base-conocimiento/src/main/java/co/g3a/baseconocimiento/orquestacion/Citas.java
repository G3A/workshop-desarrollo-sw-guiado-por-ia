package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;

/**
 * Convierte un {@link Fragmento} a la {@link Cita} que ve el usuario final. Usado por {@code
 * Orquestador} y {@code Consultador}.
 */
final class Citas {

  private static final int LONGITUD_EXTRACTO = 240;

  private Citas() {}

  static Cita desde(Fragmento f) {
    String texto = f.texto() == null ? "" : f.texto();
    String extracto =
        texto.length() > LONGITUD_EXTRACTO ? texto.substring(0, LONGITUD_EXTRACTO) + "…" : texto;
    return new Cita(f.uri(), tituloDe(f), extracto, f.tipo());
  }

  static String tituloDe(Fragmento f) {
    return (f.titulo() == null || f.titulo().isBlank()) ? f.uri() : f.titulo();
  }
}
