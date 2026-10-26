package co.g3a.baseconocimiento.orquestacion;

import static org.assertj.core.api.Assertions.assertThat;

import co.g3a.baseconocimiento.orquestacion.ValidadorDeCitas.Resultado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValidadorDeCitasTest {

  @Test
  @DisplayName("Una cita dentro de 1..k no queda fuera de rango; una mayor que k, si")
  void separaCitadosDeFueraDeRango() {
    Resultado resultado = ValidadorDeCitas.validar("Uno [1], otro [2] y uno inventado [9].", 2);

    assertThat(resultado.citados()).containsExactly(1L, 2L, 9L);
    assertThat(resultado.fueraDeRango()).containsExactly(9L);
  }

  @Test
  @DisplayName("Reconoce listas [n, m], con o sin espacios")
  void reconoceListasSeparadasPorComas() {
    Resultado resultado = ValidadorDeCitas.validar("A [1, 3]. B [2,7]. C [ 4 ].", 3);

    assertThat(resultado.citados()).containsExactly(1L, 3L, 2L, 7L, 4L);
    assertThat(resultado.fueraDeRango()).containsExactly(7L, 4L);
  }

  @Test
  @DisplayName("[1][9] suelto son dos citas")
  void citasPegadasEntreSiSonDosCitas() {
    Resultado resultado = ValidadorDeCitas.validar("Docker arranca [1][9].", 2);

    assertThat(resultado.citados()).containsExactly(1L, 9L);
    assertThat(resultado.fueraDeRango()).containsExactly(9L);
  }

  @Test
  @DisplayName("Una cadena de corchetes pegada a un identificador es indexacion, no cita")
  void noConfundeIndexacionConCitas() {
    Resultado resultado =
        ValidadorDeCitas.validar("Usa array[i], array[3], m[1][2] o iniciado[9].", 2);

    assertThat(resultado.citados()).isEmpty();
  }

  @Test
  @DisplayName("Lo que no es solo numero entre corchetes no es cita")
  void ignoraCorchetesSinNumero() {
    Resultado resultado =
        ValidadorDeCitas.validar("[] [ ] [abc] [1-3] [^9] [9abc] [1, 9abc] [Nota]", 2);

    assertThat(resultado.citados()).isEmpty();
  }

  @Test
  @DisplayName("Lo que esta dentro de codigo Markdown no es cita")
  void ignoraCodigoMarkdown() {
    String texto =
        """
        El arreglo se declara asi [1]:

        ```java
        int[] x = {10, 20};
        var ids = [10, 20];
        ```

        Y en JSON `"ids": [30]` tambien.
        """;

    Resultado resultado = ValidadorDeCitas.validar(texto, 2);

    assertThat(resultado.citados()).containsExactly(1L);
  }

  @Test
  @DisplayName("Un bloque de codigo sin cerrar o con ~~~ tampoco aporta citas")
  void ignoraBloquesSinCerrarYConVirgulillas() {
    String truncado = "Ejemplo [1]:\n```java\nvar ids = [10, 20];";
    String conVirgulillas = "Ejemplo [2]:\n~~~json\n\"ids\": [30]\n~~~\nFin.";

    assertThat(ValidadorDeCitas.validar(truncado, 2).citados()).containsExactly(1L);
    assertThat(ValidadorDeCitas.validar(conVirgulillas, 2).citados()).containsExactly(2L);
  }

  @Test
  @DisplayName("[0] y [-1] son citas fuera de rango")
  void ceroYNegativosQuedanFueraDeRango() {
    Resultado resultado = ValidadorDeCitas.validar("A [0]. B [-1]. C [1].", 2);

    assertThat(resultado.fueraDeRango()).containsExactly(0L, -1L);
  }

  @Test
  @DisplayName("Un indice repetido aparece una sola vez, en el orden de su primera cita")
  void noRepiteIndices() {
    Resultado resultado = ValidadorDeCitas.validar("A [9]. B [1]. C [9, 1].", 2);

    assertThat(resultado.citados()).containsExactly(9L, 1L);
    assertThat(resultado.fueraDeRango()).containsExactly(9L);
  }

  @Test
  @DisplayName("Con k=0 toda cita queda fuera de rango")
  void sinFragmentosTodaCitaQuedaFuera() {
    assertThat(ValidadorDeCitas.validar("A [1].", 0).fueraDeRango()).containsExactly(1L);
  }

  @Test
  @DisplayName("Texto nulo, vacio o sin citas no lanza ni marca nada")
  void textoNuloOVacioNoMarcaNada() {
    assertThat(ValidadorDeCitas.validar(null, 2).citados()).isEmpty();
    assertThat(ValidadorDeCitas.validar("", 2).citados()).isEmpty();
    assertThat(ValidadorDeCitas.validar("   ", 2).citados()).isEmpty();
    assertThat(ValidadorDeCitas.validar(Orquestador.MENSAJE_SIN_INFORMACION, 0).citados())
        .isEmpty();
  }

  @Test
  @DisplayName("Una advertencia por indice fuera de rango, con el texto fijo que lo nombra")
  void unaAdvertenciaPorIndiceFueraDeRango() {
    Resultado resultado = ValidadorDeCitas.validar("A [1]. B [7]. C [9].", 2);

    assertThat(ValidadorDeCitas.advertencias(resultado))
        .containsExactly(
            "La cita [7] no corresponde a ninguno de los fragmentos consultados.",
            "La cita [9] no corresponde a ninguno de los fragmentos consultados.");
  }

  @Test
  @DisplayName("Un numero de mas de 18 digitos no se reconoce como cita ni desborda")
  void numeroDemasiadoLargoNoEsCita() {
    assertThat(ValidadorDeCitas.validar("A [1234567890123456789].", 2).citados()).isEmpty();
  }
}
