package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * La otra mitad de {@link AdminControllerTest}: con {@code kb.ingesta.carga-habilitada=true}
 * apuntando a un vault temporal real (no al del repo), para verificar que el archivo queda escrito
 * de verdad y que un nombre fuera de la lista blanca se rechaza.
 */
@WebMvcTest(AdminController.class)
class AdminControllerCargaHabilitadaTest {

  @TempDir static Path vaultTemporal;

  static Path documentosTemporal;

  @BeforeAll
  static void crearCarpetaDocumentos() throws IOException {
    documentosTemporal = vaultTemporal.resolve("documentos");
    Files.createDirectories(documentosTemporal);
  }

  @DynamicPropertySource
  static void propiedades(DynamicPropertyRegistry registry) {
    registry.add("kb.ingesta.carga-habilitada", () -> "true");
    registry.add("kb.ingesta.vault-dir", () -> vaultTemporal.toString());
  }

  @Autowired MockMvc mockMvc;

  @MockitoBean IngestaRepositorio repo;

  @MockitoBean RelevadorDeFuentes relevador;

  @Test
  @DisplayName("Con la carga habilitada, el archivo queda escrito en vault/documentos")
  void subirArchivoEscribeEnElVault() throws Exception {
    var archivo =
        new MockMultipartFile(
            "archivo", "nuevo.md", "text/markdown", "contenido de prueba".getBytes());

    mockMvc
        .perform(multipart("/api/admin/vault/documentos").file(archivo))
        .andExpect(status().isOk());

    assertThat(Files.readString(documentosTemporal.resolve("nuevo.md")))
        .isEqualTo("contenido de prueba");
  }

  @Test
  @DisplayName("Rechaza una extension que no esta en la lista blanca")
  void subirArchivoRechazaExtensionNoPermitida() throws Exception {
    var archivo =
        new MockMultipartFile("archivo", "script.exe", "application/octet-stream", "x".getBytes());

    mockMvc
        .perform(multipart("/api/admin/vault/documentos").file(archivo))
        .andExpect(status().isBadRequest());

    assertThat(Files.exists(documentosTemporal.resolve("script.exe"))).isFalse();
  }

  @Test
  @DisplayName("Un nombre con recorrido de directorio no escapa de vault/documentos")
  void subirArchivoRechazaRecorridoDeDirectorio() throws Exception {
    var archivo =
        new MockMultipartFile("archivo", "../../etc/nuevo.md", "text/markdown", "x".getBytes());

    mockMvc
        .perform(multipart("/api/admin/vault/documentos").file(archivo))
        .andExpect(status().isOk());

    // Path.of(...).getFileName() ya descarta cualquier segmento de directorio:
    // el archivo queda dentro de vault/documentos con el nombre final, no fuera de el.
    assertThat(Files.exists(documentosTemporal.resolve("nuevo.md"))).isTrue();
    assertThat(Files.exists(documentosTemporal.getParent().resolve("etc"))).isFalse();
  }
}
