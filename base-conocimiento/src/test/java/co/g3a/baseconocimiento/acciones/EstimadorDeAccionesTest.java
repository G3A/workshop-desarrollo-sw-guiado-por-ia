package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.g3a.baseconocimiento.acciones.Acciones.Estimado;
import co.g3a.baseconocimiento.acciones.Acciones.EstimadoModo;
import co.g3a.baseconocimiento.acciones.Acciones.Modo;
import co.g3a.baseconocimiento.acciones.Acciones.Tipo;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.RendimientoDelModelo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El estimado de cada modo (issue #57) con un modelo que rinde como Granite en la T600: 3
 * caracteres por token, 18 tokens/s de entrada y 7 de salida, contexto de 4096.
 */
class EstimadorDeAccionesTest {

  private static final ProyectoId PROYECTO = new ProyectoId("default");
  private static final AccionesPropiedades PROPIEDADES =
      new AccionesPropiedades(10, 7000, 8000, 1, 10000);

  private static final RendimientoDelModelo GRANITE =
      new RendimientoDelModelo() {
        @Override
        public double caracteresPorToken() {
          return 3.0;
        }

        @Override
        public double segundosEstimados(int caracteresEntrada, int tokensSalida) {
          return caracteresEntrada / 3.0 / 18 + tokensSalida / 7.0;
        }

        @Override
        public int contextoTokens() {
          return 4096;
        }
      };

  private final SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
  private final EstimadorDeAcciones estimador =
      new EstimadorDeAcciones(repo, PROPIEDADES, GRANITE, 600);

  @Test
  @DisplayName(
      "Un documento largo: completo pasa del umbral y se ofrece elegir; capitulos y rapido cuestan"
          + " menos y cubren menos")
  void documentoLargoOfreceElegir() {
    documentoConCapitulos(6, 10, 3000);

    Estimado estimado = estimador.estimar(Tipo.RESUMIR, List.of(1L), PROYECTO);

    assertThat(estimado.elegir()).isTrue();
    assertThat(estimado.modos())
        .extracting(EstimadoModo::modo)
        .containsExactly(Modo.COMPLETO, Modo.POR_CAPITULOS, Modo.RAPIDO);
    EstimadoModo completo = estimado.modos().get(0);
    EstimadoModo capitulos = estimado.modos().get(1);
    EstimadoModo rapido = estimado.modos().get(2);
    assertThat(completo.segundos()).isGreaterThan(600);
    assertThat(completo.seccionesCubiertas()).isEqualTo(60);
    assertThat(capitulos.llamadas()).isEqualTo(7);
    assertThat(capitulos.segundos()).isLessThan(completo.segundos());
    assertThat(rapido.segundos()).isLessThan(capitulos.segundos());
    assertThat(rapido.llamadas()).isEqualTo(1);
    assertThat(rapido.seccionesCubiertas()).isEqualTo(7000 / 400);
    assertThat(rapido.seccionesTotales()).isEqualTo(60);
  }

  @Test
  @DisplayName("Si todo cabe en el contexto no hay modos que elegir")
  void documentoCortoNoOfreceElegir() {
    documentoConCapitulos(1, 2, 500);

    Estimado estimado = estimador.estimar(Tipo.RESUMIR, List.of(1L), PROYECTO);

    assertThat(estimado.elegir()).isFalse();
    assertThat(estimado.modos()).singleElement().extracting(EstimadoModo::llamadas).isEqualTo(1);
  }

  @Test
  @DisplayName("Preguntas cuenta una llamada final por nivel de Bloom")
  void preguntasSonSeisLlamadasFinales() {
    documentoConCapitulos(1, 2, 500);

    Estimado estimado = estimador.estimar(Tipo.PREGUNTAS, List.of(1L), PROYECTO);

    assertThat(estimado.modos().getFirst().llamadas()).isEqualTo(6);
  }

  private void documentoConCapitulos(int capitulos, int porCapitulo, int largo) {
    List<Seccion> secciones = new ArrayList<>();
    Map<Long, List<String>> rutas = new HashMap<>();
    for (int c = 1; c <= capitulos; c++) {
      for (int s = 1; s <= porCapitulo; s++) {
        long id = secciones.size() + 1;
        secciones.add(
            new Seccion(id, 1, "file:///jls.pdf", "jls.pdf", (int) id, "x".repeat(largo), "doc"));
        rutas.put(id, List.of("Capítulo " + c, c + "." + s));
      }
    }
    when(repo.seccionesDe(List.of(1L), "default")).thenReturn(secciones);
    when(repo.rutasDe(any(), anyString())).thenReturn(rutas);
  }
}
