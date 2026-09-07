package co.g3a.baseconocimiento.ingesta;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chunker heurístico para código fuente: clase → método → bloque, de grano grueso a fino,
 * subdividiendo SOLO cuando el fragmento excede el tamaño máximo — igual que {@link
 * ChunkerEncabezados} hace con las secciones de Markdown, pero detectando límites de clase/método
 * por regex sobre líneas en vez de niveles de encabezado.
 *
 * <p>No es un parser: no hay binding sólido de tree-sitter en Java (ver el ADR correspondiente).
 * Funciona razonablemente en lenguajes de llave (Java, C#, JS/TS, Go, C/C++); en lenguajes sin
 * llaves como Python no encuentra declaraciones y cae directo a {@link ChunkerVentanas} sobre el
 * archivo entero — limitación heurística documentada, no una decisión de diseño.
 */
final class ChunkerCodigo {

  private static final int MAX_CARACTERES_CLASE = 4_000;
  private static final int MAX_CARACTERES_METODO = 2_000;

  private static final Pattern DECLARACION_TIPO =
      Pattern.compile(
          "^\\s*(public|private|protected|internal|static|final|abstract|sealed|export)?\\s*"
              + "(class|interface|enum|record|struct)\\s+\\w+.*$");

  private static final Pattern DECLARACION_METODO =
      Pattern.compile(
          "^\\s*(public|private|protected|internal|static|final|abstract|override|async|export|default|virtual)?"
              + "[\\w<>\\[\\],. ]*\\s+\\w+\\s*\\([^;{}]*\\)\\s*\\{?\\s*$");

  private static final Pattern PALABRA_DE_CONTROL =
      Pattern.compile("^\\s*(if|for|while|switch|catch|synchronized|try|else|do)\\b.*");

  private ChunkerCodigo() {}

  /**
   * @param ruta declaración de clase (y de método, si aplica) que contextualiza este bloque; {@code
   *     null} si no se detectó ninguna
   */
  record Bloque(String ruta, String cuerpo) {}

  static List<Bloque> trocear(String texto) {
    List<String> lineas = List.of(texto.split("\\R", -1));
    List<Integer> limitesClase = indicesQueCoincidenCon(lineas, DECLARACION_TIPO, false);

    if (limitesClase.isEmpty()) {
      return ventanas(null, texto, MAX_CARACTERES_CLASE);
    }

    List<Bloque> resultado = new ArrayList<>();
    for (int i = 0; i < limitesClase.size(); i++) {
      int inicio = limitesClase.get(i);
      int fin = (i + 1 < limitesClase.size()) ? limitesClase.get(i + 1) : lineas.size();
      String declaracionClase = lineas.get(inicio).strip();
      String cuerpoClase = String.join("\n", lineas.subList(inicio, fin)).strip();

      if (cuerpoClase.length() <= MAX_CARACTERES_CLASE) {
        resultado.add(new Bloque(declaracionClase, cuerpoClase));
      } else {
        resultado.addAll(subdividirPorMetodo(declaracionClase, cuerpoClase));
      }
    }
    return resultado;
  }

  private static List<Bloque> subdividirPorMetodo(String declaracionClase, String cuerpoClase) {
    List<String> lineas = List.of(cuerpoClase.split("\\R", -1));
    List<Integer> limitesMetodo = indicesQueCoincidenCon(lineas, DECLARACION_METODO, true);

    if (limitesMetodo.isEmpty()) {
      return ventanas(declaracionClase, cuerpoClase, MAX_CARACTERES_METODO);
    }

    List<Bloque> resultado = new ArrayList<>();
    if (limitesMetodo.get(0) > 0) {
      String cabecera = String.join("\n", lineas.subList(0, limitesMetodo.get(0))).strip();
      if (!cabecera.isEmpty()) {
        resultado.add(new Bloque(declaracionClase, cabecera));
      }
    }
    for (int i = 0; i < limitesMetodo.size(); i++) {
      int inicio = limitesMetodo.get(i);
      int fin = (i + 1 < limitesMetodo.size()) ? limitesMetodo.get(i + 1) : lineas.size();
      String ruta = declaracionClase + " › " + lineas.get(inicio).strip();
      String cuerpoMetodo = String.join("\n", lineas.subList(inicio, fin)).strip();
      resultado.addAll(ventanas(ruta, cuerpoMetodo, MAX_CARACTERES_METODO));
    }
    return resultado;
  }

  private static List<Bloque> ventanas(String ruta, String texto, int maxCaracteres) {
    return ChunkerVentanas.trocear(texto, maxCaracteres).stream()
        .map(v -> new Bloque(ruta, v))
        .toList();
  }

  private static List<Integer> indicesQueCoincidenCon(
      List<String> lineas, Pattern patron, boolean excluirControl) {
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < lineas.size(); i++) {
      String linea = lineas.get(i);
      if (excluirControl && PALABRA_DE_CONTROL.matcher(linea).matches()) {
        continue;
      }
      if (patron.matcher(linea).matches()) {
        indices.add(i);
      }
    }
    return indices;
  }
}
