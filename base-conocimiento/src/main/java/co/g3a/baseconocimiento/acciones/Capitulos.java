package co.g3a.baseconocimiento.acciones;

import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parte un documento largo en capitulos para el modo «Por capitulos» (issue #57): cada capitulo es
 * una llamada al LLM con un extracto acotado de sus secciones, en vez de leer todo el texto.
 *
 * <p>El capitulo sale de la ruta de encabezados que ya dejo la ingesta, pero el nivel cambia segun
 * el documento: en la especificacion de Java el primer nivel ya es el capitulo; en la NIST SP
 * 800-53 el primer nivel es el titulo del documento y las familias de controles estan dos niveles
 * mas abajo. Por eso se prueba nivel por nivel y se queda con el primero que da entre {@link
 * #MINIMO} y {@link #MAXIMO} grupos. Sin rutas, agrupa por orden en bloques parejos.
 */
final class Capitulos {

  /** Menos que esto no es un indice: el documento entero en uno o dos bloques. */
  static final int MINIMO = 5;

  /** Mas que esto multiplica las llamadas sin ganar cobertura: el JLS tiene 20. */
  static final int MAXIMO = 40;

  /** Niveles de ruta que se prueban antes de rendirse y agrupar por orden. */
  private static final int NIVELES = 4;

  /** Tope del extracto de un capitulo: lo que entra al LLM por cada uno. */
  static final int LARGO_EXTRACTO = 6000;

  /** El largo del extracto que cabe en una pasada: nunca mas que {@link #LARGO_EXTRACTO}. */
  static int largoExtracto(int caracteresPorPasada) {
    return Math.min(LARGO_EXTRACTO, caracteresPorPasada);
  }

  /** Por debajo de esto un extracto por seccion ya no dice nada: se toman menos secciones. */
  static final int MINIMO_POR_SECCION = 200;

  record Capitulo(String titulo, List<Seccion> secciones) {}

  private Capitulos() {}

  static List<Capitulo> agrupar(List<Seccion> secciones, Map<Long, List<String>> rutas) {
    List<Capitulo> mejor = null;
    for (int nivel = 0; nivel < NIVELES; nivel++) {
      List<Capitulo> grupos = porNivel(secciones, rutas, nivel);
      if (grupos.size() >= MINIMO && grupos.size() <= MAXIMO) {
        return grupos;
      }
      if (grupos.size() <= MAXIMO && (mejor == null || grupos.size() > mejor.size())) {
        mejor = grupos;
      }
    }
    boolean sinRutas = secciones.stream().allMatch(s -> rutaDe(s, rutas).isEmpty());
    return sinRutas || mejor == null || mejor.size() < 2 ? porOrden(secciones) : mejor;
  }

  /** Secciones consecutivas con el mismo prefijo de ruta hasta {@code nivel}, en un grupo. */
  private static List<Capitulo> porNivel(
      List<Seccion> secciones, Map<Long, List<String>> rutas, int nivel) {
    List<Capitulo> grupos = new ArrayList<>();
    List<String> clave = null;
    List<Seccion> actual = new ArrayList<>();
    for (Seccion s : secciones) {
      List<String> ruta = rutaDe(s, rutas);
      // Una seccion sin ruta en medio de un capitulo (una tabla, un fragmento de codigo) es parte
      // de ese capitulo: con jls25.pdf partia «Interfaces» en tres grupos.
      if (ruta.isEmpty() && clave != null) {
        actual.add(s);
        continue;
      }
      List<String> suya = ruta.subList(0, Math.min(nivel + 1, ruta.size()));
      if (clave != null && !clave.equals(suya)) {
        grupos.add(new Capitulo(titulo(clave), actual));
        actual = new ArrayList<>();
      }
      clave = suya;
      actual.add(s);
    }
    if (clave != null) {
      grupos.add(new Capitulo(titulo(clave), actual));
    }
    return grupos;
  }

  private static List<Capitulo> porOrden(List<Seccion> secciones) {
    int bloques = Math.max(1, Math.min(MAXIMO, Math.max(MINIMO, secciones.size() / 5)));
    int porBloque = (int) Math.ceil(secciones.size() / (double) bloques);
    List<Capitulo> grupos = new ArrayList<>();
    for (int i = 0; i < secciones.size(); i += porBloque) {
      int fin = Math.min(secciones.size(), i + porBloque);
      grupos.add(
          new Capitulo("Secciones " + (i + 1) + "–" + fin, List.copyOf(secciones.subList(i, fin))));
    }
    return grupos;
  }

  /**
   * Lo que entra al LLM por un capitulo: por cada seccion que se toma, su titulo y el comienzo de
   * su texto. Si son muchas para el largo, se toman repartidas a lo largo del capitulo, no las
   * primeras.
   */
  static String extracto(Capitulo capitulo, Map<Long, List<String>> rutas, int largo) {
    List<Seccion> todas = capitulo.secciones();
    int caben = Math.max(1, largo / MINIMO_POR_SECCION);
    List<Seccion> tomadas = new ArrayList<>();
    for (int i = 0; i < Math.min(caben, todas.size()); i++) {
      tomadas.add(todas.get((int) ((long) i * todas.size() / Math.min(caben, todas.size()))));
    }
    int porSeccion = largo / tomadas.size();
    StringBuilder sb = new StringBuilder();
    for (Seccion s : tomadas) {
      List<String> ruta = rutaDe(s, rutas);
      String cabecera = "- " + (ruta.isEmpty() ? "Sección " + s.ord() : ruta.getLast()) + ": ";
      String cuerpo = s.texto() == null ? "" : s.texto().replaceAll("\\s+", " ").strip();
      int resto = Math.max(0, porSeccion - cabecera.length() - 1);
      sb.append(cabecera).append(cuerpo, 0, Math.min(cuerpo.length(), resto)).append('\n');
    }
    return sb.length() <= largo ? sb.toString() : sb.substring(0, largo);
  }

  /**
   * La ruta de lo ya indexado, sacada del {@code summary} que escribio la ingesta ({@code "A › B:
   * texto"}): antes de #57 no se guardaba aparte. Sin {@code " › "} solo se acepta un prefijo corto
   * y sin punto, para no tomar por titulo el comienzo de una oracion.
   */
  static List<String> rutaDelSummary(String summary) {
    if (summary == null) {
      return List.of();
    }
    int ultimaFlecha = summary.lastIndexOf(" › ");
    int dosPuntos = summary.indexOf(": ", Math.max(0, ultimaFlecha));
    if (dosPuntos < 0) {
      return List.of();
    }
    String prefijo = summary.substring(0, dosPuntos);
    if (ultimaFlecha < 0 && (prefijo.length() > 120 || prefijo.contains("."))) {
      return List.of();
    }
    return List.of(prefijo.split(" › "));
  }

  private static List<String> rutaDe(Seccion s, Map<Long, List<String>> rutas) {
    return rutas.getOrDefault(s.id(), List.of());
  }

  private static String titulo(List<String> clave) {
    return clave.isEmpty() ? "Sin título" : clave.getLast();
  }
}
