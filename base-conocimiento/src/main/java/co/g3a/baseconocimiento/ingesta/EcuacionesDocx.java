package co.g3a.baseconocimiento.ingesta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Antes de pasar un DOCX por Tika, cambia cada ecuación OMML del cuerpo por una marca alfanumérica
 * y guarda su LaTeX. Después de Tika, {@link #restaurar} cambia cada marca por {@code $…$} (dentro
 * del texto) o {@code $$…$$} (ecuación sola en su párrafo).
 *
 * <p>La marca existe porque el {@code ToMarkdownContentHandler} de Tika escapa {@code \ _ ^ { }}:
 * el LaTeX escrito directo en el documento saldría roto.
 */
final class EcuacionesDocx {

  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String MARCA = "OMMLECUACION%dX";

  /**
   * Las partes del DOCX que Tika lee como texto: el cuerpo, encabezados y pies de página (uno por
   * sección), notas al pie y al final, y comentarios. Una ecuación puede estar en cualquiera.
   */
  private static final java.util.regex.Pattern PARTES_CON_TEXTO =
      java.util.regex.Pattern.compile(
          "word/(document|header\\d*|footer\\d*|footnotes|endnotes|comments)\\.xml");

  record Preparado(byte[] docx, List<String> latex) {}

  private EcuacionesDocx() {}

  static Preparado preparar(byte[] docx) throws Exception {
    List<String> latex = new ArrayList<>();
    ByteArrayOutputStream salida = new ByteArrayOutputStream(docx.length);
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx));
        ZipOutputStream out = new ZipOutputStream(salida)) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        byte[] datos = in.readAllBytes();
        if (PARTES_CON_TEXTO.matcher(e.getName()).matches()) {
          datos = reemplazar(datos, latex);
        }
        out.putNextEntry(new ZipEntry(e.getName()));
        out.write(datos);
        out.closeEntry();
      }
    }
    return latex.isEmpty()
        ? new Preparado(docx, latex)
        : new Preparado(salida.toByteArray(), latex);
  }

  static String restaurar(String markdown, List<String> latex) {
    for (int i = latex.size() - 1; i >= 0; i--) {
      // Tika escribe como &#32; el espacio con que la marca abre un párrafo.
      markdown =
          markdown
              .replace("&#32;" + MARCA.formatted(i), latex.get(i))
              .replace(MARCA.formatted(i), latex.get(i));
    }
    return markdown;
  }

  private static byte[] reemplazar(byte[] xml, List<String> latex) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    // El DOCX viene de afuera: sin DTD ni entidades externas (XXE).
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

    // Copia estática: la NodeList de getElementsByTagNameNS es viva y cambia al reemplazar.
    NodeList vivas = doc.getElementsByTagNameNS(OmmlALatex.NS, "oMath");
    List<Element> ecuaciones = new ArrayList<>();
    for (int i = 0; i < vivas.getLength(); i++) {
      ecuaciones.add((Element) vivas.item(i));
    }
    if (ecuaciones.isEmpty()) {
      return xml;
    }

    for (Element oMath : ecuaciones) {
      // Una ecuación anidada dentro de otra ya quedó convertida con su contenedor.
      if (dentroDeOtraEcuacion(oMath) || oMath.getParentNode() == null) {
        continue;
      }
      Node padre = oMath.getParentNode();
      boolean bloque =
          OmmlALatex.NS.equals(padre.getNamespaceURI()) && "oMathPara".equals(padre.getLocalName());
      String tex = OmmlALatex.convertir(oMath);
      latex.add(bloque ? "$$" + tex + "$$" : "$" + tex + "$");

      Element run = doc.createElementNS(W, "w:r");
      Element t = doc.createElementNS(W, "w:t");
      t.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
      t.setTextContent(
          (bloque ? "" : " ") + MARCA.formatted(latex.size() - 1) + (bloque ? "" : " "));
      run.appendChild(t);
      padre.replaceChild(run, oMath);
    }
    // oMathPara queda con w:r adentro: se desarma para que los runs cuelguen del párrafo.
    NodeList paras = doc.getElementsByTagNameNS(OmmlALatex.NS, "oMathPara");
    List<Element> lista = new ArrayList<>();
    for (int i = 0; i < paras.getLength(); i++) {
      lista.add((Element) paras.item(i));
    }
    for (Element p : lista) {
      Node padre = p.getParentNode();
      while (p.getFirstChild() != null) {
        Node h = p.getFirstChild();
        p.removeChild(h);
        if (h instanceof Element he && W.equals(he.getNamespaceURI())) {
          padre.insertBefore(h, p);
        }
      }
      padre.removeChild(p);
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream(xml.length);
    TransformerFactory.newInstance()
        .newTransformer()
        .transform(new DOMSource(doc), new StreamResult(out));
    return out.toByteArray();
  }

  private static boolean dentroDeOtraEcuacion(Element e) {
    for (Node n = e.getParentNode(); n != null; n = n.getParentNode()) {
      if (OmmlALatex.NS.equals(n.getNamespaceURI()) && "oMath".equals(n.getLocalName())) {
        return true;
      }
    }
    return false;
  }
}
