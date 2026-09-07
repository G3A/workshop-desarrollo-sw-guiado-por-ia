package co.g3a.baseconocimiento.ingesta;

import java.util.ArrayList;
import java.util.List;

/**
 * Trocea texto plano por párrafos, agrupándolos en ventanas de hasta {@code maxCaracteres}.
 *
 * <p>Es el respaldo para lo que no trae una estructura reconocible: el texto que sale de un PDF no
 * conserva encabezados fiables, así que no hay con qué alimentar a {@link ChunkerEncabezados}.
 * Documentado como limitación heurística, no como una decisión de diseño — ver el ADR
 * correspondiente.
 */
final class ChunkerVentanas {

  private ChunkerVentanas() {}

  static List<String> trocear(String texto, int maxCaracteres) {
    List<String> parrafos = List.of(texto.split("\\n\\s*\\n"));
    List<String> ventanas = new ArrayList<>();
    StringBuilder actual = new StringBuilder();

    for (String parrafo : parrafos) {
      String p = parrafo.strip();
      if (p.isEmpty()) {
        continue;
      }
      if (p.length() > maxCaracteres) {
        // Un solo parrafo ya excede el maximo (tipico de PDFs sin saltos
        // de linea reales): se corta por caracteres como ultimo recurso.
        cerrarVentana(ventanas, actual);
        for (int i = 0; i < p.length(); i += maxCaracteres) {
          ventanas.add(p.substring(i, Math.min(i + maxCaracteres, p.length())));
        }
        continue;
      }
      if (actual.length() + p.length() + 2 > maxCaracteres && !actual.isEmpty()) {
        cerrarVentana(ventanas, actual);
      }
      if (!actual.isEmpty()) {
        actual.append("\n\n");
      }
      actual.append(p);
    }
    cerrarVentana(ventanas, actual);

    return ventanas.isEmpty() ? List.of(texto.strip()) : ventanas;
  }

  private static void cerrarVentana(List<String> ventanas, StringBuilder actual) {
    if (!actual.isEmpty()) {
      ventanas.add(actual.toString());
      actual.setLength(0);
    }
  }
}
