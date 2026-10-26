package co.g3a.baseconocimiento.orquestacion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marca las citas {@code [n]} de una respuesta que no corresponden a ninguno de los {@code k}
 * fragmentos que recibió el sintetizador ({@code construirContexto} los numera {@code [1]..[k]}).
 * Es la alucinación más barata de detectar: determinista, sin segunda llamada al LLM. No dice si la
 * cita es <em>fiel</em> (si el fragmento respalda lo afirmado) — eso no es determinista.
 *
 * <p>Solo lo usa el camino bloqueante ({@code Orquestador.ejecutar}, para {@code /api/ask} y
 * Teams). {@code ejecutarEnStreaming} no valida: el texto sale token a token antes de que exista
 * completo, y mostrar la advertencia en {@code index.html} es un issue aparte (fuera de #52).
 *
 * <p>Qué cuenta como cita, fijado por {@code ValidadorDeCitasTest}:
 *
 * <ul>
 *   <li>{@code [n]} y listas {@code [n, m, ...]}, con espacios opcionales; {@code [1][9]} suelto
 *       son dos citas. {@code [0]} y {@code [-1]} cuentan, y quedan fuera de rango (el sintetizador
 *       estructurado arma {@code [%d]} con un entero que decide el modelo).
 *   <li>No cuenta una cadena de corchetes pegada a un identificador ({@code array[3]}, {@code
 *       m[1][2]}, {@code iniciado[9]}), lo que no es solo número ({@code [1-3]}, {@code [^9]},
 *       {@code [9abc]}), ni nada dentro de código Markdown (bloques con tres acentos graves o tres
 *       virgulillas, aunque queden sin cerrar, y código en línea): el corpus trae Java y JSON, y
 *       {@code [10, 20]} ahí es un literal, no una cita.
 *   <li>Hasta 18 dígitos por índice, para que quepa en un {@code long}.
 * </ul>
 */
final class ValidadorDeCitas {

  /** El texto fijo de cada advertencia; {@code %d} es el índice citado. */
  static final String MENSAJE_CITA_FUERA_DE_RANGO =
      "La cita [%d] no corresponde a ninguno de los fragmentos consultados.";

  // Un bloque sin cerrar (la sintesis cortada por max-tokens a mitad de codigo)
  // llega hasta el final del texto.
  private static final Pattern CODIGO_EN_BLOQUE =
      Pattern.compile("```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)", Pattern.DOTALL);
  private static final Pattern CODIGO_EN_LINEA = Pattern.compile("`[^`\\n]*`");
  private static final Pattern INDEXACION =
      Pattern.compile("[\\p{L}\\p{N}_](?:\\[[^\\[\\]\\n]*\\])+");
  private static final Pattern CITA =
      Pattern.compile("\\[\\s*(-?\\d{1,18}(?:\\s*,\\s*-?\\d{1,18})*)\\s*\\]");

  private ValidadorDeCitas() {}

  /**
   * @param citados los índices citados, sin repetir, en orden de primera aparición
   * @param fueraDeRango los de {@code citados} que no están en {@code 1..k}, en el mismo orden
   */
  record Resultado(List<Long> citados, List<Long> fueraDeRango) {}

  /** {@code texto} nulo o vacío no es un error: no cita nada. */
  static Resultado validar(String texto, int k) {
    if (texto == null || texto.isBlank()) {
      return new Resultado(List.of(), List.of());
    }
    String prosa = CODIGO_EN_BLOQUE.matcher(texto).replaceAll(" ");
    prosa = CODIGO_EN_LINEA.matcher(prosa).replaceAll(" ");
    prosa = INDEXACION.matcher(prosa).replaceAll(" ");

    Set<Long> citados = new LinkedHashSet<>();
    Matcher cita = CITA.matcher(prosa);
    while (cita.find()) {
      for (String indice : cita.group(1).split(",")) {
        citados.add(Long.parseLong(indice.strip()));
      }
    }
    List<Long> fueraDeRango = new ArrayList<>();
    for (long indice : citados) {
      if (indice < 1 || indice > k) {
        fueraDeRango.add(indice);
      }
    }
    return new Resultado(List.copyOf(citados), List.copyOf(fueraDeRango));
  }

  /** Una advertencia por índice fuera de rango, con {@link #MENSAJE_CITA_FUERA_DE_RANGO}. */
  static List<String> advertencias(Resultado resultado) {
    return resultado.fueraDeRango().stream()
        .map(indice -> MENSAJE_CITA_FUERA_DE_RANGO.formatted(indice))
        .toList();
  }
}
