package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ExtractorOffice} contra archivos reales del set de pruebas de Docling (ver {@code
 * src/test/resources/office/README.md}), sin dobles: Tika y POI corren en el proceso.
 */
class ExtractorOfficeTest {

  private final ExtractorOffice extractor = new ExtractorOffice();

  @Test
  @DisplayName("Las ecuaciones de Word salen en LaTeX, en línea y en bloque")
  void ecuacionesEnLatex() throws Exception {
    String md = extraer("equations.docx");

    assertThat(md)
        .contains("$A=\\pi r^{2}$")
        .contains("$$a^{2}+b^{2}=c^{2} \\times 23$$")
        .contains("\\sum_{n=1}^{\\infty }")
        .doesNotContain("OMMLECUACION")
        .doesNotContain("A=πr2");
  }

  @Test
  @DisplayName("Las imágenes incrustadas no aparecen como encabezados")
  void sinEncabezadosDeAdjuntos() throws Exception {
    String md = extraer("word_image_anchors.docx");

    assertThat(md).contains("This is test 1").doesNotContain("# image");
  }

  @Test
  @DisplayName("Tika no deja entidades de espacio en blanco (&#9; &#32;)")
  void sinEntidadesDeEspacio() throws Exception {
    assertThat(extraer("equations.docx")).doesNotContain("&#");
  }

  @Test
  @DisplayName("PPTX: una sección por diapositiva, con tablas y notas del orador")
  void pptxConTablasYNotas() throws Exception {
    String md = extraer("powerpoint_sample.pptx");

    assertThat(md)
        .startsWith("## Test Table Slide")
        .contains("## Second slide title")
        .contains("## Diapositiva 3")
        .contains("| R1 | True | False |")
        .contains("> **Notas del orador:** Some notes on the second slide.");
    assertThat(ChunkerEncabezados.trocear(md))
        .extracting(s -> String.join(" > ", s.rutaEncabezados()))
        .contains("Second slide title");
  }

  @Test
  @DisplayName("PPTX: el título de portada (CENTERED_TITLE) no se pierde")
  void tituloDePortada() throws Exception {
    assertThat(extraer("powerpoint_bad_text.pptx")).startsWith("## X-Library");
  }

  private String extraer(String archivo) throws Exception {
    return extractor.extraerMarkdown(
        archivo, Files.readAllBytes(Path.of("src/test/resources/office", archivo)));
  }
}
