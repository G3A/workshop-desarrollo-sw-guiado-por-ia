package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;

import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las secciones representativas del modo «Rapido» (issue #57): con embeddings, una por tema; sin
 * ellos, repartidas a lo largo del documento.
 */
class RepresentativasTest {

  private final List<Seccion> secciones = new ArrayList<>();
  private final Map<Long, float[]> vectores = new HashMap<>();

  @Test
  @DisplayName("Tres temas en bloques de distinto largo: una seccion de cada uno, en orden")
  void unaPorTema() {
    temas(new int[] {30, 5, 12});

    List<Seccion> elegidas = Representativas.elegir(secciones, vectores, 3);

    assertThat(elegidas).extracting(Seccion::texto).containsExactly("tema 0", "tema 1", "tema 2");
    assertThat(elegidas).isSortedAccordingTo((a, b) -> Integer.compare(a.ord(), b.ord()));
  }

  @Test
  @DisplayName("La misma seleccion da siempre las mismas secciones")
  void determinista() {
    temas(new int[] {20, 20, 20, 20});

    assertThat(Representativas.elegir(secciones, vectores, 4))
        .isEqualTo(Representativas.elegir(secciones, vectores, 4));
  }

  @Test
  @DisplayName("Sin embeddings suficientes, secciones repartidas incluida la primera")
  void sinEmbeddingsReparte() {
    temas(new int[] {40});
    vectores.clear();

    assertThat(Representativas.elegir(secciones, vectores, 4))
        .extracting(Seccion::ord)
        .containsExactly(0, 10, 20, 30);
  }

  @Test
  @DisplayName("Si el documento tiene menos secciones que las pedidas, van todas")
  void pocasSecciones() {
    temas(new int[] {3});

    assertThat(Representativas.elegir(secciones, vectores, 10)).hasSize(3);
  }

  /** Cada tema es un eje: sus secciones apuntan casi todas hacia el, con un poco de ruido. */
  private void temas(int[] cuantas) {
    int dim = cuantas.length + 1;
    for (int t = 0; t < cuantas.length; t++) {
      for (int i = 0; i < cuantas[t]; i++) {
        int ord = secciones.size();
        secciones.add(new Seccion(ord + 1, 1, "file:///d", "d", ord, "tema " + t, "doc"));
        float[] v = new float[dim];
        v[t] = 1;
        v[dim - 1] = (i % 7) * 0.01f;
        vectores.put((long) ord + 1, v);
      }
    }
  }
}
