package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubseccionesNumeradasTest {

  @Test
  @DisplayName("Una viñeta numerada hija de un encabezado real pasa a encabezado un nivel debajo")
  void promueveLaVinetaHijaDeUnEncabezadoReal() {
    var texto =
        """
                #### 4.2 Primitive Types and Values
                Texto de 4.2.
                - 4.2.1 Integral Types and Values
                Texto de 4.2.1.
                - 4.2.2 Integer Operations
                Texto de 4.2.2.
                ##### 4.2.3 Floating-Point Types
                Texto de 4.2.3.
                """;

    String resultado = SubseccionesNumeradas.promover(texto);

    assertThat(resultado)
        .contains("##### 4.2.1 Integral Types and Values\n")
        .contains("##### 4.2.2 Integer Operations\n")
        .contains("##### 4.2.3 Floating-Point Types\n");
    assertThat(ChunkerEncabezados.trocear(resultado))
        .extracting(s -> String.join(" > ", s.rutaEncabezados()))
        .containsExactly(
            "4.2 Primitive Types and Values",
            "4.2 Primitive Types and Values > 4.2.1 Integral Types and Values",
            "4.2 Primitive Types and Values > 4.2.2 Integer Operations",
            "4.2 Primitive Types and Values > 4.2.3 Floating-Point Types");
  }

  @Test
  @DisplayName("Las entradas del índice, que terminan en número de página, no se tocan")
  void noTocaElIndice() {
    var texto =
        """
                ### 4 Types, Values, and Variables 57
                - 4.1 The Kinds of Types and Values 58
                - 4.2 Primitive Types and Values 58
                """;

    assertThat(SubseccionesNumeradas.promover(texto)).isEqualTo(texto);
  }

  @Test
  @DisplayName("Sin un encabezado padre real, una viñeta numerada sigue siendo viñeta")
  void sinPadreNoPromueve() {
    var texto =
        """
                #### 3.1 Unicode
                - 4.2.1 Integral Types and Values
                - 1. primer paso
                """;

    assertThat(SubseccionesNumeradas.promover(texto)).isEqualTo(texto);
  }

  @Test
  @DisplayName("Una frase tomada como título vuelve a ser párrafo: termina en punto o abre un [")
  void degradaFrasesTomadasComoTitulo() {
    // Casos reales de la NIST SP 800-53 por OpenDataLoader: partían el control AC-2 en dos.
    var texto =
        """
                ###### AC-2 ACCOUNT MANAGEMENT
                ###### Disable accounts within [Assignment
                ###### Employ automated mechanisms to monitor and control remote access methods.
                ###### Example 4.2.2-1. Integer Operations
                ###### 4.2.1 Integral Types and Values The values are integers in these ranges:
                """;

    assertThat(SubseccionesNumeradas.promover(texto))
        .contains("###### AC-2 ACCOUNT MANAGEMENT\n")
        .contains("\nDisable accounts within [Assignment\n")
        .contains("\nEmploy automated mechanisms to monitor and control remote access methods.\n")
        .contains("###### Example 4.2.2-1. Integer Operations\n")
        .contains(
            "###### 4.2.1 Integral Types and Values The values are integers in these ranges:");
  }

  @Test
  @DisplayName("Una subsección promovida sirve de padre a la siguiente profundidad")
  void laPromovidaSirveDePadre() {
    var texto =
        """
                #### 13.4 Evolution of Classes
                - 13.4.2 sealed Classes
                - 13.4.2.1 sealed Classes detail
                """;

    assertThat(SubseccionesNumeradas.promover(texto))
        .contains("##### 13.4.2 sealed Classes\n")
        .contains("###### 13.4.2.1 sealed Classes detail\n");
  }
}
