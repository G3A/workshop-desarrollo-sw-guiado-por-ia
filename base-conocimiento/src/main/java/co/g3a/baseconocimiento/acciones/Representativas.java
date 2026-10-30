package co.g3a.baseconocimiento.acciones;

import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Las secciones que representan un documento largo en el modo «Rapido» (issue #57): k-means sobre
 * los embeddings que ya dejo la ingesta y, de cada grupo, la seccion mas cercana a su centro. Asi
 * la unica llamada al LLM ve un poco de cada tema del documento, no solo el comienzo.
 *
 * <p>Sin embeddings suficientes (el worker todavia no los calculo) se toman secciones repartidas a
 * lo largo del documento. Determinista: la semilla es fija, la misma seleccion da el mismo
 * resultado.
 */
final class Representativas {

  /** Por debajo de esta fraccion de secciones con embedding, no vale la pena agrupar. */
  static final double MINIMO_CON_EMBEDDING = 0.5;

  /** Caracteres por seccion: con la cuota del documento, define cuantas representativas caben. */
  static final int LARGO_POR_SECCION = 400;

  private static final int ITERACIONES = 25;
  private static final long SEMILLA = 57;

  private Representativas() {}

  /** Cuantas secciones representativas caben en {@code cuota} caracteres. */
  static int cuantas(int cuota) {
    return Math.max(1, cuota / LARGO_POR_SECCION);
  }

  /**
   * @return a lo sumo {@code k} secciones, en el orden del documento
   */
  static List<Seccion> elegir(List<Seccion> secciones, Map<Long, float[]> vectores, int k) {
    if (secciones.size() <= k) {
      return secciones;
    }
    List<Seccion> conVector = secciones.stream().filter(s -> vectores.containsKey(s.id())).toList();
    if (conVector.size() < k || conVector.size() < secciones.size() * MINIMO_CON_EMBEDDING) {
      return repartidas(secciones, k);
    }
    double[][] puntos = new double[conVector.size()][];
    for (int i = 0; i < puntos.length; i++) {
      puntos[i] = normalizado(vectores.get(conVector.get(i).id()));
    }
    int[] grupo = kMeans(puntos, k);
    double[][] centros = centros(puntos, grupo, k);
    List<Seccion> elegidas = new ArrayList<>();
    for (int c = 0; c < k; c++) {
      int mejor = -1;
      double menor = Double.MAX_VALUE;
      for (int i = 0; i < puntos.length; i++) {
        if (grupo[i] == c && distancia(puntos[i], centros[c]) < menor) {
          menor = distancia(puntos[i], centros[c]);
          mejor = i;
        }
      }
      if (mejor >= 0) {
        elegidas.add(conVector.get(mejor));
      }
    }
    elegidas.sort(Comparator.comparingInt(secciones::indexOf));
    return elegidas;
  }

  /** {@code k} secciones a intervalos parejos, incluida la primera. */
  static List<Seccion> repartidas(List<Seccion> secciones, int k) {
    List<Seccion> elegidas = new ArrayList<>();
    for (int i = 0; i < k; i++) {
      elegidas.add(secciones.get((int) ((long) i * secciones.size() / k)));
    }
    return elegidas;
  }

  /** k-means++ para arrancar y Lloyd hasta que nada cambia o se agotan las iteraciones. */
  private static int[] kMeans(double[][] puntos, int k) {
    Random azar = new Random(SEMILLA);
    double[][] centros = new double[k][];
    centros[0] = puntos[azar.nextInt(puntos.length)].clone();
    double[] cercania = new double[puntos.length];
    for (int c = 1; c < k; c++) {
      double total = 0;
      for (int i = 0; i < puntos.length; i++) {
        double d = Double.MAX_VALUE;
        for (int j = 0; j < c; j++) {
          d = Math.min(d, distancia(puntos[i], centros[j]));
        }
        cercania[i] = d;
        total += d;
      }
      double umbral = azar.nextDouble() * total;
      int elegido = puntos.length - 1;
      for (int i = 0; i < puntos.length; i++) {
        umbral -= cercania[i];
        if (umbral <= 0) {
          elegido = i;
          break;
        }
      }
      centros[c] = puntos[elegido].clone();
    }
    int[] grupo = new int[puntos.length];
    for (int vuelta = 0; vuelta < ITERACIONES; vuelta++) {
      boolean cambio = false;
      for (int i = 0; i < puntos.length; i++) {
        int mejor = 0;
        for (int c = 1; c < k; c++) {
          if (distancia(puntos[i], centros[c]) < distancia(puntos[i], centros[mejor])) {
            mejor = c;
          }
        }
        cambio |= grupo[i] != mejor;
        grupo[i] = mejor;
      }
      if (!cambio && vuelta > 0) {
        break;
      }
      double[][] nuevos = centros(puntos, grupo, k);
      for (int c = 0; c < k; c++) {
        if (nuevos[c] != null) {
          centros[c] = nuevos[c];
        }
      }
    }
    return grupo;
  }

  /** El promedio de cada grupo; {@code null} para un grupo vacio. */
  private static double[][] centros(double[][] puntos, int[] grupo, int k) {
    int dim = puntos[0].length;
    double[][] sumas = new double[k][dim];
    int[] cuantos = new int[k];
    for (int i = 0; i < puntos.length; i++) {
      cuantos[grupo[i]]++;
      for (int d = 0; d < dim; d++) {
        sumas[grupo[i]][d] += puntos[i][d];
      }
    }
    double[][] centros = new double[k][];
    for (int c = 0; c < k; c++) {
      if (cuantos[c] > 0) {
        centros[c] = sumas[c];
        for (int d = 0; d < dim; d++) {
          centros[c][d] /= cuantos[c];
        }
      }
    }
    return centros;
  }

  /** Normalizados, la distancia euclidea ordena igual que la de coseno con que se indexa. */
  private static double[] normalizado(float[] v) {
    double norma = 0;
    for (float x : v) {
      norma += x * x;
    }
    norma = Math.sqrt(norma);
    double[] r = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      r[i] = norma == 0 ? 0 : v[i] / norma;
    }
    return r;
  }

  private static double distancia(double[] a, double[] b) {
    double suma = 0;
    for (int i = 0; i < a.length; i++) {
      double d = a[i] - b[i];
      suma += d * d;
    }
    return suma;
  }
}
