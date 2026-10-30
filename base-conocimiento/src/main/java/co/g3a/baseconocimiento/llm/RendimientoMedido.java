package co.g3a.baseconocimiento.llm;

import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link RendimientoDelModelo} con una ventana de las ultimas llamadas medidas. Los caracteres por
 * token salen de sumar caracteres y tokens de entrada; el costo por token, de un ajuste por minimos
 * cuadrados de {@code segundos = a × entrada + b × salida}. Si las muestras no alcanzan para
 * separar {@code a} de {@code b} (todas con la misma salida, por ejemplo), {@code b} queda en el
 * default y solo se ajusta {@code a}.
 *
 * <p>Los defaults son lo medido con Granite 4.1 3B Q3_K_M en la T600 (ADR-0015): valen hasta que
 * llega la primera muestra. Lo alimenta {@link RedactorOpenAi} con el {@code usage} de cada llamada
 * de lectura.
 */
@Component
class RendimientoMedido implements RendimientoDelModelo {

  /** Muestras recientes: el rendimiento cambia si cambia el modelo o la carga de la GPU. */
  private static final int VENTANA = 30;

  private record Muestra(int caracteres, int tokensEntrada, int tokensSalida, double segundos) {}

  private final double caracteresPorTokenPorDefecto;
  private final double segundosPorTokenEntradaPorDefecto;
  private final double segundosPorTokenSalidaPorDefecto;
  private final int contextoTokens;
  private final Deque<Muestra> muestras = new ArrayDeque<>();

  RendimientoMedido(
      @Value("${kb.llm.rendimiento.caracteres-por-token:3.0}") double caracteresPorToken,
      @Value("${kb.llm.rendimiento.tokens-entrada-por-segundo:18}") double entradaPorSegundo,
      @Value("${kb.llm.rendimiento.tokens-salida-por-segundo:7}") double salidaPorSegundo,
      @Value("${kb.llm.contexto-tokens:4096}") int contextoTokens) {
    this.caracteresPorTokenPorDefecto = caracteresPorToken;
    this.segundosPorTokenEntradaPorDefecto = 1 / entradaPorSegundo;
    this.segundosPorTokenSalidaPorDefecto = 1 / salidaPorSegundo;
    this.contextoTokens = contextoTokens;
  }

  @Override
  public int contextoTokens() {
    return contextoTokens;
  }

  /** Una llamada medida; sin tokens de entrada (el servidor no devolvio usage) no aporta nada. */
  synchronized void registrar(
      int caracteresEntrada, int tokensEntrada, int tokensSalida, long milisegundos) {
    if (tokensEntrada <= 0 || caracteresEntrada <= 0 || milisegundos <= 0) {
      return;
    }
    muestras.addLast(
        new Muestra(
            caracteresEntrada, tokensEntrada, Math.max(0, tokensSalida), milisegundos / 1000.0));
    while (muestras.size() > VENTANA) {
      muestras.removeFirst();
    }
  }

  @Override
  public synchronized double caracteresPorToken() {
    long caracteres = 0;
    long tokens = 0;
    for (Muestra m : muestras) {
      caracteres += m.caracteres();
      tokens += m.tokensEntrada();
    }
    return tokens == 0 ? caracteresPorTokenPorDefecto : (double) caracteres / tokens;
  }

  @Override
  public synchronized double segundosEstimados(int caracteresEntrada, int tokensSalida) {
    double[] costo = costoPorToken();
    double tokensEntrada = caracteresEntrada / caracteresPorToken();
    return tokensEntrada * costo[0] + Math.max(0, tokensSalida) * costo[1];
  }

  /** {@code {a, b}}: segundos por token de entrada y de salida. */
  private double[] costoPorToken() {
    if (muestras.isEmpty()) {
      return new double[] {segundosPorTokenEntradaPorDefecto, segundosPorTokenSalidaPorDefecto};
    }
    double ee = 0;
    double es = 0;
    double ss = 0;
    double et = 0;
    double st = 0;
    for (Muestra m : muestras) {
      double e = m.tokensEntrada();
      double s = m.tokensSalida();
      ee += e * e;
      es += e * s;
      ss += s * s;
      et += e * m.segundos();
      st += s * m.segundos();
    }
    double det = ee * ss - es * es;
    if (muestras.size() >= 3 && det > 1e-6 * ee * Math.max(ss, 1)) {
      double a = (et * ss - st * es) / det;
      double b = (ee * st - es * et) / det;
      if (a > 0 && b > 0) {
        return new double[] {a, b};
      }
    }
    // No se pueden separar: b queda en el default y a se ajusta con el resto del tiempo.
    double b = segundosPorTokenSalidaPorDefecto;
    double a = (et - b * es) / ee;
    return new double[] {a > 0 ? a : segundosPorTokenEntradaPorDefecto, b};
  }
}
