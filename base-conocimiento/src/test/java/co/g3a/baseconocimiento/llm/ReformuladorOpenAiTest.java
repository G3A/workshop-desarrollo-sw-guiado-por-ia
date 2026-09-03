package co.g3a.baseconocimiento.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Solo la depuración de candidatas, que es lo único de {@link ReformuladorOpenAi} que no depende de
 * un LLM vivo: que la lista que llega a la persona no repita la pregunta ni se repita a sí misma.
 */
class ReformuladorOpenAiTest {

  @Test
  @DisplayName("Descarta la propia pregunta (sin importar mayusculas ni espacios) y los duplicados")
  void descartaLaPreguntaYLosDuplicados() {
    List<String> depuradas =
        ReformuladorOpenAi.depurar(
            "que es el autoboxing",
            List.of(
                "  Que es el autoboxing ",
                "boxing conversion",
                "Boxing Conversion",
                " autoboxing Java "));

    assertThat(depuradas).containsExactly("boxing conversion", "autoboxing Java");
  }

  @Test
  @DisplayName("Ignora nulos y vacios, y corta en MAX_ALTERNATIVAS")
  void ignoraVaciosYCortaEnElMaximo() {
    List<String> depuradas =
        ReformuladorOpenAi.depurar(
            "pregunta", Arrays.asList(null, "", "   ", "uno", "dos", "tres", "cuatro"));

    assertThat(depuradas).containsExactly("uno", "dos", "tres");
    assertThat(depuradas).hasSize(ReformuladorOpenAi.MAX_ALTERNATIVAS);
  }

  @Test
  @DisplayName(
      "Sin pistas el mensaje es la pregunta sola; con pistas van rotuladas y despues de la pregunta")
  void elMensajeDeUsuarioSoloAgregaLasPistasCuandoLasHay() {
    assertThat(ReformuladorOpenAi.mensajeDeUsuario("que es static", List.of()))
        .isEqualTo("que es static");

    String conPistas =
        ReformuladorOpenAi.mensajeDeUsuario(
            "que es static", List.of("[jls25.pdf] A class variable is...", "[jls25.pdf] static"));

    assertThat(conPistas)
        .startsWith("Pregunta: que es static\n\n")
        .contains("pistas del vocabulario de la fuente")
        .contains("- [jls25.pdf] A class variable is...\n")
        .endsWith("- [jls25.pdf] static\n");
  }

  @Test
  @DisplayName("Sin candidatas utiles, la Reformulacion queda sin cambios")
  void sinCandidatasUtilesNoReformula() {
    var reformulacion =
        new Reformulador.Reformulacion(
            "pregunta", ReformuladorOpenAi.depurar("pregunta", List.of("PREGUNTA")));

    assertThat(reformulacion.reformulada()).isFalse();
    assertThat(reformulacion.textoBusqueda()).isEqualTo("pregunta");
  }
}
