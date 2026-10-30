package co.g3a.baseconocimiento.ingesta;

import java.util.regex.Pattern;

/**
 * Quita el escape de espacios en blanco que el {@code ToMarkdownContentHandler} de Tika escribe
 * como entidades HTML. Tika lo hace para que la sangría no convierta un párrafo en bloque de
 * código; aquí la sangría no aporta nada al RAG, así que se elimina en vez de escaparla.
 */
final class LimpiezaMarkdown {

  /** Sangría al inicio de línea: escapada (&#32; &#9;) o real, en cualquier mezcla. */
  private static final Pattern SANGRIA = Pattern.compile("(?m)^(?:&#32;|&#9;|[ \\t])+");

  /** Espacios en blanco escapados dentro de la línea (p. ej. saltos en el alt de una imagen). */
  private static final Pattern ESCAPADOS = Pattern.compile("(?:&#(?:9|10|13|32);)+");

  private static final Pattern LINEAS_VACIAS = Pattern.compile("\\n{3,}");

  private LimpiezaMarkdown() {}

  static String limpiar(String markdown) {
    String s = SANGRIA.matcher(markdown).replaceAll("");
    s = ESCAPADOS.matcher(s).replaceAll(" ");
    return LINEAS_VACIAS.matcher(s).replaceAll("\n\n");
  }
}
