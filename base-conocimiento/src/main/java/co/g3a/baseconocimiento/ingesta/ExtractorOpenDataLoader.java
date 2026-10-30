package co.g3a.baseconocimiento.ingesta;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.springframework.stereotype.Component;

/**
 * Piloto: extrae Markdown de un PDF con OpenDataLoader PDF, en el mismo proceso y sin GPU, en vez
 * de mandarlo a docling-serve. Solo PDF: la librería no lee DOCX ni PPTX, que siguen por {@link
 * ExtractorDocling}. Se elige con {@code kb.ingesta.motor-pdf=opendataloader}.
 *
 * <p>Usa el modo local (determinista, sin el modo híbrido que delega páginas difíciles a un backend
 * de IA). Medido con jls25.pdf (~900 páginas) fuera de la app: 11,5 s y 378 MB de heap en CPU,
 * frente a los ~26 min de docling-serve en CPU (ADR-0010).
 *
 * <p>La API pública solo convierte desde un archivo y escribe el resultado en una carpeta, así que
 * cada llamada usa un directorio temporal propio y lo borra al terminar.
 */
@Component
class ExtractorOpenDataLoader {

  /**
   * {@code synchronized} por precaución: {@link OpenDataLoaderPDF#processFile} es estático y
   * comparte pools internos, y su documentación no dice que admita llamadas concurrentes. La
   * ingesta ya corre una fuente a la vez, así que no cuesta nada.
   */
  synchronized String extraerMarkdown(String nombreArchivo, byte[] bytes) {
    Path dir = null;
    try {
      dir = Files.createTempDirectory("kb-odl-");
      // Solo el nombre, nunca una ruta que venga en el argumento.
      Path entrada = dir.resolve(Path.of(nombreArchivo).getFileName().toString());
      Files.write(entrada, bytes);
      Path salida = Files.createDirectory(dir.resolve("salida"));

      Config config = new Config();
      config.setOutputFolder(salida.toString());
      config.setGenerateMarkdown(true);
      config.setGenerateJSON(false);
      config.setImageOutput(Config.IMAGE_OUTPUT_OFF);
      OpenDataLoaderPDF.processFile(entrada.toString(), config);

      try (Stream<Path> archivos = Files.list(salida)) {
        Path markdown =
            archivos
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .findFirst()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "OpenDataLoader no generó Markdown para " + nombreArchivo));
        return SubseccionesNumeradas.promover(Files.readString(markdown, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("OpenDataLoader no pudo convertir " + nombreArchivo, e);
    } finally {
      borrar(dir);
    }
  }

  /** La documentación pide llamarlo una sola vez, al cerrar la aplicación. */
  @PreDestroy
  void cerrar() {
    OpenDataLoaderPDF.shutdown();
  }

  private static void borrar(Path dir) {
    if (dir == null) {
      return;
    }
    try (Stream<Path> todo = Files.walk(dir)) {
      todo.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException e) {
      // Un temporal que no se pudo borrar no debe tumbar la ingesta.
    }
  }
}
