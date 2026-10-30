package co.g3a.baseconocimiento.ingesta;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corrige un defecto de OpenDataLoader PDF: algunas subsecciones numeradas ({@code 4.2.1 Integral
 * Types and Values}) le salen como viñeta en vez de encabezado, y entonces {@link
 * ChunkerEncabezados} las pega a la sección de arriba en un solo chunk que mezcla temas.
 *
 * <p>Una viñeta {@code - 4.2.1 Título} pasa a encabezado solo si su padre ({@code 4.2}) ya apareció
 * como encabezado real, y queda un nivel debajo de él. Esa exigencia es la que evita convertir
 * listas numeradas comunes. Las entradas del índice no se tocan: terminan en número de página.
 *
 * <p>Corrige también el defecto opuesto: una oración resaltada en el PDF que OpenDataLoader toma
 * por título ({@code Disable accounts within [Assignment}, en la NIST SP 800-53). Al mismo nivel
 * que un encabezado real, lo reemplazaba en la ruta y el resto de la sección perdía su contexto;
 * vuelve a ser un párrafo.
 */
final class SubseccionesNumeradas {

  private static final Pattern ENCABEZADO =
      Pattern.compile("^(#{1,6})\\s+(\\d+(?:\\.\\d+)*)\\s.*$");
  private static final Pattern VINETA = Pattern.compile("^-\\s+(\\d+(?:\\.\\d+)+)\\s+(.+?)\\s*$");
  private static final Pattern TERMINA_EN_PAGINA = Pattern.compile(".*\\s\\d+$");
  private static final Pattern CUALQUIER_ENCABEZADO = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");

  private SubseccionesNumeradas() {}

  static String promover(String markdown) {
    // Número de sección -> nivel del encabezado que lo declaró.
    Map<String, Integer> niveles = new HashMap<>();
    StringBuilder salida = new StringBuilder(markdown.length() + 1024);

    for (String linea : markdown.split("\n", -1)) {
      Matcher cualquiera = CUALQUIER_ENCABEZADO.matcher(linea);
      if (cualquiera.matches() && esFrase(cualquiera.group(1))) {
        linea = cualquiera.group(1);
      }
      Matcher encabezado = ENCABEZADO.matcher(linea);
      Matcher vineta = VINETA.matcher(linea);
      if (encabezado.matches()) {
        niveles.put(encabezado.group(2), encabezado.group(1).length());
      } else if (vineta.matches() && !TERMINA_EN_PAGINA.matcher(linea).matches()) {
        String numero = vineta.group(1);
        Integer nivelPadre = niveles.get(numero.substring(0, numero.lastIndexOf('.')));
        if (nivelPadre != null && nivelPadre < 6) {
          int nivel = nivelPadre + 1;
          niveles.put(numero, nivel);
          linea = "#".repeat(nivel) + " " + numero + " " + vineta.group(2);
        }
      }
      salida.append(linea).append('\n');
    }
    // split con -1 deja un último elemento vacío; no agregar un salto de línea extra.
    salida.setLength(salida.length() - 1);
    return salida.toString();
  }

  /**
   * Un título no termina en punto ni deja un corchete abierto; una oración cortada sí. Las 4
   * palabras mínimas dejan pasar títulos cortos con abreviatura final ("Vol. 2.").
   */
  private static boolean esFrase(String texto) {
    long abre = texto.chars().filter(c -> c == '[').count();
    long cierra = texto.chars().filter(c -> c == ']').count();
    if (abre > cierra) {
      return true;
    }
    return texto.endsWith(".") && !texto.endsWith("..") && texto.split("\\s+").length >= 4;
  }
}
