package co.g3a.baseconocimiento.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que el modelo rinde, aprendido de las llamadas reales (issue #57): cuántos caracteres hay en
 * un token y cuánto cuesta cada token de entrada y de salida. Sin muestras vale el default medido
 * con Granite 4.1 3B en la T600; con muestras, lo que el modelo de verdad hizo.
 */
class RendimientoMedidoTest {

  private static final double CARACTERES_POR_TOKEN = 3.0;
  private static final double ENTRADA_POR_SEGUNDO = 18;
  private static final double SALIDA_POR_SEGUNDO = 7;

  private final RendimientoMedido rendimiento =
      new RendimientoMedido(CARACTERES_POR_TOKEN, ENTRADA_POR_SEGUNDO, SALIDA_POR_SEGUNDO, 4096);

  @Test
  @DisplayName("Sin muestras usa los valores por defecto")
  void sinMuestrasUsaLosDefaults() {
    assertThat(rendimiento.caracteresPorToken()).isEqualTo(3.0);
    // 9000 caracteres = 3000 tokens a 18/s, más 70 tokens de salida a 7/s.
    assertThat(rendimiento.segundosEstimados(9000, 70)).isCloseTo(3000 / 18.0 + 10, within(0.01));
  }

  @Test
  @DisplayName("Con muestras aprende los caracteres por token y el costo de entrada y de salida")
  void aprendeDeLasLlamadasReales() {
    // Un modelo que lee a 20 tokens/s, escribe a 5 tokens/s y rinde 2,5 caracteres por token.
    int[][] llamadas = {{3700, 70}, {3160, 32}, {1962, 120}, {2800, 45}, {3500, 200}};
    for (int[] l : llamadas) {
      int tokensEntrada = l[0];
      int tokensSalida = l[1];
      long ms = Math.round((tokensEntrada / 20.0 + tokensSalida / 5.0) * 1000);
      rendimiento.registrar((int) (tokensEntrada * 2.5), tokensEntrada, tokensSalida, ms);
    }

    assertThat(rendimiento.caracteresPorToken()).isCloseTo(2.5, within(0.01));
    assertThat(rendimiento.segundosEstimados(10_000, 100))
        .isCloseTo(4000 / 20.0 + 100 / 5.0, within(1.0));
  }

  @Test
  @DisplayName("Con muestras que no separan entrada de salida, sigue dando un estimado positivo")
  void muestrasDegeneradasNoRompenElEstimado() {
    // Todas con la misma salida: no se puede separar el costo de un token de salida.
    for (int i = 0; i < 4; i++) {
      rendimiento.registrar(9000, 3000, 0, 150_000);
    }

    double segundos = rendimiento.segundosEstimados(9000, 50);
    assertThat(segundos).isPositive();
    assertThat(segundos).isCloseTo(150 + 50 / SALIDA_POR_SEGUNDO, within(1.0));
  }

  @Test
  @DisplayName("Una llamada sin usage (tokens en 0) no se registra")
  void ignoraMuestrasSinTokens() {
    rendimiento.registrar(9000, 0, 0, 1000);

    assertThat(rendimiento.caracteresPorToken()).isEqualTo(3.0);
  }
}
