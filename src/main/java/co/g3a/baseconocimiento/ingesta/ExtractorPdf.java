package co.g3a.baseconocimiento.ingesta;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Extrae texto de PDF en Java puro: Apache PDFBox, sin depender de un binario
 * del sistema operativo (a diferencia de {@code pdftotext}).
 */
@Component
class ExtractorPdf {

    String extraerTexto(Path archivo) throws IOException {
        try (var documento = Loader.loadPDF(archivo.toFile())) {
            var extractor = new PDFTextStripper();
            extractor.setSortByPosition(true);
            return extractor.getText(documento);
        }
    }
}
