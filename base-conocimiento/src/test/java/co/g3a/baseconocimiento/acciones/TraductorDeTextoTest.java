package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.g3a.baseconocimiento.acciones.Acciones.TextoTraducido;
import co.g3a.baseconocimiento.llm.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * La traduccion de un texto del chat (modo traducir y «Traducir» por turno), con el LLM doblado.
 */
class TraductorDeTextoTest {

  private static final AccionesPropiedades PROPIEDADES =
      new AccionesPropiedades(10, 7000, 8000, 1, 10000);

  @Test
  @DisplayName("Con origen nulo detecta una sola vez y traduce con lo detectado")
  void detectaUnaVez() {
    Redactor redactor = mock(Redactor.class);
    when(redactor.detectarIdioma("hola")).thenReturn("es");
    when(redactor.traducir("hola", "es", "en")).thenReturn(Flux.just("hel", "lo"));
    var cupo = new CupoDeAcciones(PROPIEDADES);
    var traductor = new TraductorDeTexto(redactor, cupo);

    TextoTraducido resultado = traductor.traducir("hola", null, "en");

    assertThat(resultado.idiomaOrigen().block()).isEqualTo("es");
    assertThat(String.join("", resultado.texto().collectList().block())).isEqualTo("hello");
    assertThat(resultado.idiomaDestino()).isEqualTo("en");
    verify(redactor).detectarIdioma("hola");
    assertThat(cupo.intentarTomar()).isTrue();
  }

  @Test
  @DisplayName(
      "Con origen explicito no detecta; si coincide con el destino devuelve el texto tal cual")
  void origenExplicito() {
    Redactor redactor = mock(Redactor.class);
    when(redactor.traducir("hola", "es", "pt")).thenReturn(Flux.just("olá"));
    var traductor = new TraductorDeTexto(redactor, new CupoDeAcciones(PROPIEDADES));

    assertThat(
            String.join("", traductor.traducir("hola", "es", "pt").texto().collectList().block()))
        .isEqualTo("olá");
    assertThat(
            String.join("", traductor.traducir("hola", "es", "es").texto().collectList().block()))
        .isEqualTo("hola");
    verify(redactor, never()).detectarIdioma(any());
    verify(redactor, never()).traducir("hola", "es", "es");
  }

  @Test
  @DisplayName("'und' detectado no se toma como igual al destino: se traduce igual")
  void undSeTraduce() {
    Redactor redactor = mock(Redactor.class);
    when(redactor.detectarIdioma("???")).thenReturn("und");
    when(redactor.traducir("???", "und", "und")).thenReturn(Flux.just("?"));
    var traductor = new TraductorDeTexto(redactor, new CupoDeAcciones(PROPIEDADES));

    // Destino "und" no llega hasta aqui (el adaptador lo rechaza), pero aunque llegara
    // la comparacion no puede omitir por "und": no es un idioma.
    assertThat(
            String.join("", traductor.traducir("???", null, "und").texto().collectList().block()))
        .isEqualTo("?");
  }

  @Test
  @DisplayName(
      "Nada corre hasta suscribirse, y el cupo vuelve con exito, con error y sin cupo hay mensaje")
  void perezosoYCupo() {
    Redactor redactor = mock(Redactor.class);
    when(redactor.detectarIdioma("hola")).thenReturn("es");
    when(redactor.traducir("hola", "es", "en"))
        .thenReturn(Flux.error(new IllegalStateException("Ollama no responde")));
    var cupo = new CupoDeAcciones(PROPIEDADES);
    var traductor = new TraductorDeTexto(redactor, cupo);

    TextoTraducido resultado = traductor.traducir("hola", null, "en");
    verify(redactor, never()).detectarIdioma(any());

    assertThatThrownBy(() -> resultado.texto().blockLast())
        .isInstanceOf(IllegalStateException.class);
    assertThat(cupo.intentarTomar()).as("el cupo volvio tras el error").isTrue();

    var sinCupo =
        new TraductorDeTexto(
            redactor, new CupoDeAcciones(new AccionesPropiedades(10, 7000, 8000, 0, 10000)));
    assertThatThrownBy(() -> sinCupo.traducir("hola", "es", "en").texto().blockLast())
        .isInstanceOf(Acciones.ServidorOcupado.class)
        .hasMessageContaining(AccionesSobreDocumentos.MENSAJE_SERVIDOR_OCUPADO);
  }
}
