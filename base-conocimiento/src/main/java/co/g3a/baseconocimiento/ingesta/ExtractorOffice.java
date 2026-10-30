package co.g3a.baseconocimiento.ingesta;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToMarkdownContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

/**
 * Extrae Markdown de DOCX y PPTX en el proceso, sin docling-serve (ADR-0014). Se elige con {@code
 * kb.ingesta.motor-office=java}.
 *
 * <ul>
 *   <li><b>DOCX</b>: Apache Tika y su {@code ToMarkdownContentHandler}, que convierte los estilos
 *       «Título N» de Word en encabezados. Antes, {@link EcuacionesDocx} pasa las ecuaciones a
 *       LaTeX; después, {@link LimpiezaMarkdown} quita la sangría que Tika escapa.
 *   <li><b>PPTX</b>: Apache POI directo, porque Tika no emite los títulos de diapositiva
 *       (TIKA-1108). Una sección {@code ##} por diapositiva, en orden, con sus tablas y las notas
 *       del orador.
 * </ul>
 *
 * <p>Medido con 60 archivos (el set de pruebas de Docling y documentos reales del de Apache POI):
 * 43 DOCX en 5 s frente a 628 s de docling-serve, sin errores, con el 99,4 % del texto que extrae
 * docling y además encabezados y pies de página, comentarios y notas, que docling no extrae.
 */
@Component
class ExtractorOffice {

  private final AutoDetectParser tika = new AutoDetectParser();

  String extraerMarkdown(String nombreArchivo, byte[] bytes) {
    try {
      return nombreArchivo.toLowerCase(java.util.Locale.ROOT).endsWith(".pptx")
          ? pptx(bytes)
          : docx(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("No se pudo convertir " + nombreArchivo, e);
    } catch (Exception e) {
      // Tika, SAX o el XML del DOCX: para la ingesta, todos son "este archivo no se pudo leer".
      throw new IllegalStateException("No se pudo convertir " + nombreArchivo + ": " + e, e);
    }
  }

  private String docx(byte[] bytes) throws Exception {
    EcuacionesDocx.Preparado preparado = EcuacionesDocx.preparar(bytes);
    ToMarkdownContentHandler markdown = new ToMarkdownContentHandler();
    ParseContext contexto = new ParseContext();
    // Sin esto Tika abre cada adjunto incrustado (imágenes, hojas de cálculo) y escribe su nombre
    // como un encabezado "# image1.jpeg".
    contexto.set(EmbeddedDocumentExtractor.class, new SinAdjuntos());
    try (TikaInputStream in = TikaInputStream.get(preparado.docx())) {
      tika.parse(in, markdown, new Metadata(), contexto);
    }
    return LimpiezaMarkdown.limpiar(
        EcuacionesDocx.restaurar(markdown.toString(), preparado.latex()));
  }

  private static String pptx(byte[] bytes) throws IOException {
    StringBuilder md = new StringBuilder();
    try (XMLSlideShow presentacion = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
      for (XSLFSlide diapositiva : presentacion.getSlides()) {
        String titulo = titulo(diapositiva);
        md.append("## ")
            .append(
                titulo == null
                    ? "Diapositiva " + diapositiva.getSlideNumber()
                    : titulo.replaceAll("\\s+", " ").strip())
            .append("\n\n");
        formas(diapositiva.getShapes(), md);
        notas(diapositiva.getNotes(), md);
      }
    }
    return md.toString();
  }

  /**
   * {@code getTitle()} de POI solo mira el placeholder {@code TITLE}: el de una portada ({@code
   * CENTERED_TITLE}) devuelve null, y ese texto se perdía (pasó en 2 de las 17 presentaciones de
   * prueba).
   */
  private static String titulo(XSLFSlide diapositiva) {
    for (XSLFShape forma : diapositiva.getShapes()) {
      if (forma instanceof XSLFTextShape texto && esTitulo(texto) && !texto.getText().isBlank()) {
        return texto.getText();
      }
    }
    return null;
  }

  private static void formas(List<XSLFShape> formas, StringBuilder md) {
    for (XSLFShape forma : formas) {
      if (forma instanceof XSLFTextShape texto) {
        String t = texto.getText().strip();
        if (!esTitulo(texto) && !t.isEmpty()) {
          md.append(t).append("\n\n");
        }
      } else if (forma instanceof XSLFTable tabla) {
        tabla(tabla, md);
      } else if (forma instanceof XSLFGroupShape grupo) {
        formas(grupo.getShapes(), md);
      }
    }
  }

  private static void tabla(XSLFTable tabla, StringBuilder md) {
    boolean primera = true;
    for (XSLFTableRow fila : tabla.getRows()) {
      md.append('|');
      fila.getCells()
          .forEach(
              c ->
                  md.append(' ')
                      .append(c.getText().replace("\n", " ").replace("|", "\\|"))
                      .append(" |"));
      md.append('\n');
      if (primera) {
        md.append('|').append(" --- |".repeat(fila.getCells().size())).append('\n');
        primera = false;
      }
    }
    md.append('\n');
  }

  /** El cuerpo de la página de notas, sin el número de diapositiva ni la miniatura. */
  private static void notas(XSLFNotes notas, StringBuilder md) {
    if (notas == null) {
      return;
    }
    StringBuilder texto = new StringBuilder();
    for (XSLFShape forma : notas.getShapes()) {
      if (forma instanceof XSLFTextShape t && t.getPlaceholder() == Placeholder.BODY) {
        String s = t.getText().strip();
        if (!s.isEmpty()) {
          texto.append(s).append('\n');
        }
      }
    }
    if (!texto.isEmpty()) {
      md.append("> **Notas del orador:** ")
          .append(texto.toString().strip().replace("\n", "\n> "))
          .append("\n\n");
    }
  }

  private static boolean esTitulo(XSLFTextShape forma) {
    Placeholder p = forma.getPlaceholder();
    return p == Placeholder.TITLE || p == Placeholder.CENTERED_TITLE;
  }

  /** No abre ni nombra los adjuntos incrustados: solo interesa el texto del documento. */
  private static final class SinAdjuntos implements EmbeddedDocumentExtractor {
    @Override
    public boolean shouldParseEmbedded(Metadata metadata, ParseContext contexto) {
      return false;
    }

    @Override
    public void parseEmbedded(
        TikaInputStream flujo,
        ContentHandler handler,
        Metadata metadata,
        ParseContext contexto,
        boolean salidaHtml) {
      // Nada: shouldParseEmbedded ya dijo que no.
    }
  }
}
