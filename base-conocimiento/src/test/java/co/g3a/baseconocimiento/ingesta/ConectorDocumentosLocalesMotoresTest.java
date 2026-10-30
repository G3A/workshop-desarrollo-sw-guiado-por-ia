package co.g3a.baseconocimiento.ingesta;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Qué extractor recibe cada extensión según {@code kb.ingesta.motor-pdf} y {@code
 * kb.ingesta.motor-office} (ADR-0014). Sin Spring ni base: el repositorio va doblado.
 */
class ConectorDocumentosLocalesMotoresTest {

  @TempDir Path vault;

  private final IngestaRepositorio repo = mock(IngestaRepositorio.class);
  private final ExtractorDocling docling = mock(ExtractorDocling.class);
  private final ExtractorOpenDataLoader openDataLoader = mock(ExtractorOpenDataLoader.class);
  private final ExtractorOffice office = mock(ExtractorOffice.class);

  @BeforeEach
  void archivos() throws IOException {
    Path documentos = Files.createDirectories(vault.resolve("documentos"));
    for (String nombre : new String[] {"a.pdf", "b.docx", "c.pptx", "d.md"}) {
      Files.writeString(documentos.resolve(nombre), "contenido de " + nombre);
    }
    when(openDataLoader.extraerMarkdown(any(), any())).thenReturn("# pdf");
    when(office.extraerMarkdown(any(), any())).thenReturn("# office");
    when(docling.submitirTarea(any(), any())).thenReturn("tarea");
    when(docling.esperarYExtraer(any(), any())).thenReturn("# docling");
  }

  @Test
  @DisplayName("Con los motores Java, ningún archivo pasa por docling-serve")
  void motoresJava() {
    conector("opendataloader", "java").ingerir();

    verify(openDataLoader).extraerMarkdown(eq("a.pdf"), any());
    verify(office).extraerMarkdown(eq("b.docx"), any());
    verify(office).extraerMarkdown(eq("c.pptx"), any());
    verify(docling, never()).submitirTarea(any(), any());
  }

  @Test
  @DisplayName("Por defecto todo va a docling-serve, como antes del ADR-0014")
  void porDefectoDocling() {
    conector("docling", "docling").ingerir();

    verify(docling).submitirTarea(eq("a.pdf"), any());
    verify(docling).submitirTarea(eq("b.docx"), any());
    verify(docling).submitirTarea(eq("c.pptx"), any());
    verify(openDataLoader, never()).extraerMarkdown(any(), any());
    verify(office, never()).extraerMarkdown(any(), any());
  }

  @Test
  @DisplayName("Los motores son independientes: PDF por OpenDataLoader y Office por docling")
  void motoresIndependientes() {
    conector("opendataloader", "docling").ingerir();

    verify(openDataLoader).extraerMarkdown(eq("a.pdf"), any());
    verify(docling).submitirTarea(eq("b.docx"), any());
    verify(office, never()).extraerMarkdown(any(), any());
  }

  private ConectorDocumentosLocales conector(String motorPdf, String motorOffice) {
    return new ConectorDocumentosLocales(
        repo, docling, openDataLoader, office, vault.toString(), motorPdf, motorOffice);
  }
}
