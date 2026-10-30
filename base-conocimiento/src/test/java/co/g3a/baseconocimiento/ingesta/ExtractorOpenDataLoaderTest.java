package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ExtractorOpenDataLoader} contra un PDF real del corpus, sin dobles: la librería corre en
 * el proceso y en CPU, así que no hace falta levantar nada. Lo que importa es que el Markdown
 * traiga encabezados reales, porque de eso vive {@link ChunkerEncabezados}.
 */
class ExtractorOpenDataLoaderTest {

  private static final ExtractorOpenDataLoader EXTRACTOR = new ExtractorOpenDataLoader();

  @AfterAll
  static void cerrar() {
    EXTRACTOR.cerrar();
  }

  @Test
  @DisplayName("Convierte un PDF a Markdown con encabezados que ChunkerEncabezados puede trocear")
  void convierteUnPdfAMarkdownConEncabezados() throws Exception {
    byte[] pdf = Files.readAllBytes(Path.of("corpus/muestra-docling.pdf"));

    String markdown = EXTRACTOR.extraerMarkdown("muestra-docling.pdf", pdf);

    assertThat(markdown).contains("# The Java® Language Specification");
    assertThat(ChunkerEncabezados.trocear(markdown))
        .anySatisfy(s -> assertThat(s.rutaEncabezados()).contains("Table of Contents"));
  }

  @Test
  @DisplayName("Un nombre con ruta no escapa del directorio temporal")
  void ignoraLaRutaDelNombreDeArchivo() throws Exception {
    byte[] pdf = Files.readAllBytes(Path.of("corpus/muestra-docling.pdf"));

    String markdown = EXTRACTOR.extraerMarkdown("../../fuera/muestra.pdf", pdf);

    assertThat(markdown).isNotBlank();
    assertThat(Path.of("../../fuera/muestra.pdf")).doesNotExist();
  }
}
