package co.g3a.baseconocimiento.ingesta;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link ContenidoVaultController} contra un vault temporal real (no el del repo): resolver la uri
 * exige tocar disco, no hay nada que doblar con un mock salvo {@link
 * IngestaRepositorio#existeDocumentoConUri}, que sí necesita una base de datos real — se dobla en
 * true/false según lo que cada prueba quiera verificar.
 */
@WebMvcTest(ContenidoVaultController.class)
class ContenidoVaultControllerTest {

  @TempDir static Path vaultTemporal;

  static Path documentosTemporal;

  @BeforeAll
  static void crearArchivoDeMuestra() throws IOException {
    documentosTemporal = vaultTemporal.resolve("documentos");
    Files.createDirectories(documentosTemporal);
    Files.writeString(documentosTemporal.resolve("manual.md"), "# Manual\ncontenido de prueba");
    Files.writeString(documentosTemporal.resolve("sin-indexar.md"), "contenido nunca ingerido");
  }

  @DynamicPropertySource
  static void propiedades(DynamicPropertyRegistry registry) {
    registry.add("kb.ingesta.vault-dir", () -> vaultTemporal.toString());
  }

  @Autowired MockMvc mockMvc;

  @MockitoBean IngestaRepositorio repo;

  @Test
  @DisplayName(
      "Sirve el archivo inline cuando la uri resuelve dentro del vault y esta indexada, como text/markdown")
  void sirveArchivoInline() throws Exception {
    when(repo.existeDocumentoConUri(eq("file:///vault/documentos/manual.md"))).thenReturn(true);

    mockMvc
        .perform(get("/api/vault/contenido").param("uri", "file:///vault/documentos/manual.md"))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
        .andExpect(
            header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/markdown")))
        .andExpect(content().string("# Manual\ncontenido de prueba"));
  }

  @Test
  @DisplayName("Con descargar=true agrega Content-Disposition attachment")
  void sirveArchivoParaDescargar() throws Exception {
    when(repo.existeDocumentoConUri(eq("file:///vault/documentos/manual.md"))).thenReturn(true);

    mockMvc
        .perform(
            get("/api/vault/contenido")
                .param("uri", "file:///vault/documentos/manual.md")
                .param("descargar", "true"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
        .andExpect(
            header()
                .string("Content-Disposition", org.hamcrest.Matchers.containsString("manual.md")));
  }

  @Test
  @DisplayName("404 si el archivo no existe")
  void archivoInexistenteDevuelve404() throws Exception {
    mockMvc
        .perform(get("/api/vault/contenido").param("uri", "file:///vault/documentos/no-existe.md"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("404 si la uri no viene del vault")
  void uriFueraDelVaultDevuelve404() throws Exception {
    mockMvc
        .perform(get("/api/vault/contenido").param("uri", "https://dev.azure.com/algo"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Un recorrido de directorio en la uri no escapa del vault")
  void recorridoDeDirectorioNoEscapaDelVault() throws Exception {
    mockMvc
        .perform(
            get("/api/vault/contenido").param("uri", "file:///vault/documentos/../../secreto.txt"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("404 si el archivo existe en disco pero nunca quedo indexado como documento")
  void archivoNoIndexadoDevuelve404AunqueExistaEnDisco() throws Exception {
    when(repo.existeDocumentoConUri(eq("file:///vault/documentos/sin-indexar.md")))
        .thenReturn(false);

    mockMvc
        .perform(
            get("/api/vault/contenido").param("uri", "file:///vault/documentos/sin-indexar.md"))
        .andExpect(status().isNotFound());
  }
}
