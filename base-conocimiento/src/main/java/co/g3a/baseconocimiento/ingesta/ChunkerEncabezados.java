package co.g3a.baseconocimiento.ingesta;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trocea Markdown por encabezados: cada sección (desde un {@code #} hasta el siguiente de igual o
 * menor nivel) es un chunk, con su ruta de encabezados como contexto.
 *
 * <p>Es la contraparte de "Confluence pages chunked around headings" del artículo de Cerebras — sin
 * llamar al LLM, porque el documento ya viene estructurado por quien lo escribió.
 */
final class ChunkerEncabezados {

  private static final Pattern ENCABEZADO = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

  /** Si una sección supera esto, se subdivide con {@link ChunkerVentanas}. */
  private static final int MAX_CARACTERES_SECCION = 4_000;

  private ChunkerEncabezados() {}

  /**
   * @param rutaEncabezados encabezados desde la raíz hasta este, en orden; vacío si el texto no
   *     tenía ningún encabezado
   */
  record Seccion(List<String> rutaEncabezados, String cuerpo) {}

  static List<Seccion> trocear(String texto) {
    List<Seccion> secciones = new ArrayList<>();
    // Encabezados vigentes en este punto del documento, de la raíz hacia adentro.
    // Cada uno guarda su nivel: un documento puede saltar niveles (un h1 seguido
    // de h4, como la especificación de Java convertida a Markdown), y entonces la
    // posición en la pila ya no dice el nivel.
    List<Encabezado> pila = new ArrayList<>();
    StringBuilder cuerpo = new StringBuilder();

    for (String linea : texto.split("\\R", -1)) {
      Matcher m = ENCABEZADO.matcher(linea);
      if (m.matches()) {
        cerrarSeccion(secciones, pila, cuerpo);
        int nivel = m.group(1).length();
        String titulo = m.group(2).trim();
        boolean enumerado = ENUMERADO.matcher(titulo).lookingAt();
        while (!pila.isEmpty() && cierra(pila.getLast(), nivel, enumerado)) {
          pila.removeLast();
        }
        pila.add(new Encabezado(nivel, titulo, enumerado));
      } else {
        cuerpo.append(linea).append('\n');
      }
    }
    cerrarSeccion(secciones, pila, cuerpo);

    return secciones.isEmpty() ? List.of(new Seccion(List.of(), texto)) : secciones;
  }

  private record Encabezado(int nivel, String titulo, boolean enumerado) {}

  /**
   * Un encabezado que abre con un enumerador ({@code (13) …}, {@code (c) …}) es un inciso del
   * encabezado de su mismo nivel que lo precede, no un hermano que lo reemplaza. Pasa en normas y
   * estándares (la NIST SP 800-53 por OpenDataLoader: cada mejora del control sale al mismo nivel
   * que el control), y como Markdown no tiene nivel 7, el inciso de un h6 no puede bajar uno.
   */
  private static final Pattern ENUMERADO = Pattern.compile("\\((\\d{1,3}|[a-z]{1,2})\\)\\s");

  /** ¿El encabezado nuevo cierra al que está en la cima de la pila? */
  private static boolean cierra(Encabezado cima, int nivel, boolean enumerado) {
    if (cima.nivel() != nivel) {
      return cima.nivel() > nivel;
    }
    // Mismo nivel: un inciso solo cierra al inciso anterior, no al encabezado que lo contiene.
    return !enumerado || cima.enumerado();
  }

  private static void cerrarSeccion(
      List<Seccion> secciones, List<Encabezado> pila, StringBuilder cuerpo) {
    String texto = cuerpo.toString().strip();
    cuerpo.setLength(0);
    if (texto.isEmpty()) {
      return;
    }
    List<String> ruta = pila.stream().map(Encabezado::titulo).toList();
    if (texto.length() <= MAX_CARACTERES_SECCION) {
      secciones.add(new Seccion(ruta, texto));
    } else {
      // Seccion demasiado grande para un solo chunk: se subdivide por
      // ventanas, conservando la misma ruta de encabezados en cada trozo.
      for (String ventana : ChunkerVentanas.trocear(texto, MAX_CARACTERES_SECCION)) {
        secciones.add(new Seccion(ruta, ventana));
      }
    }
  }
}
