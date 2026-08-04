package co.g3a.baseconocimiento.ingesta;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.g3a.baseconocimiento.ingesta.IngestaRepositorio.ConteoEstado;
import co.g3a.baseconocimiento.ingesta.IngestaRepositorio.FuenteAdmin;
import co.g3a.baseconocimiento.ingesta.RelevadorDeFuentes.ResultadoRelevo;

/**
 * {@link AdminController} contra un slice de MVC, con {@link IngestaRepositorio}
 * y {@link RelevadorDeFuentes} doblados. La carga de archivos habilitada tiene
 * su propia clase ({@link AdminControllerCargaHabilitadaTest}): necesita otra
 * propiedad y un directorio real.
 */
@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    IngestaRepositorio repo;

    @MockitoBean
    RelevadorDeFuentes relevador;

    @Test
    @DisplayName("GET /api/admin/fuentes junta cada fuente con el ultimo resultado de su tipo")
    void fuentesJuntaFuenteYUltimoResultado() throws Exception {
        var fuente = new FuenteAdmin(1L, "local_docs", "corpus", "default", true, Instant.EPOCH, 900, 3, 10);
        when(repo.listarFuentes()).thenReturn(List.of(fuente));
        var resultado = new ResultadoRelevo("local_docs", true, "resumen", null);
        when(relevador.ultimoResultado("local_docs")).thenReturn(Optional.of(resultado));

        mockMvc.perform(get("/api/admin/fuentes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"kind\":\"local_docs\"")))
                .andExpect(content().string(containsString("\"tipo\":\"local_docs\"")));
    }

    @Test
    @DisplayName("POST /api/admin/fuentes/{tipo}/reindexar delega en RelevadorDeFuentes")
    void reindexarDelegaEnRelevador() throws Exception {
        when(relevador.relevar("local_docs")).thenReturn(new ResultadoRelevo("local_docs", true, "ok", null));

        mockMvc.perform(post("/api/admin/fuentes/local_docs/reindexar"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"ejecutado\":true")));

        verify(relevador).relevar(eq("local_docs"));
    }

    @Test
    @DisplayName("GET /api/admin/cola devuelve los conteos por estado")
    void colaDevuelveConteos() throws Exception {
        when(repo.contarTrabajosPorEstado()).thenReturn(List.of(new ConteoEstado("pending", 5)));

        mockMvc.perform(get("/api/admin/cola"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"estado\":\"pending\"")));
    }

    @Test
    @DisplayName("GET /api/admin/ayuda expone las rutas y el intervalo reales, no un valor inventado")
    void ayudaExponeValoresReales() throws Exception {
        mockMvc.perform(get("/api/admin/ayuda"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"documentosDir\"")))
                .andExpect(content().string(containsString("\"relevoIntervaloMs\":900000")))
                .andExpect(content().string(containsString("\"cargaHabilitada\":false")));
    }

    @Test
    @DisplayName("GET /api/admin/proyectos devuelve los project_id que existen de verdad")
    void proyectosDevuelveLosProjectIdReales() throws Exception {
        when(repo.listarProyectos()).thenReturn(List.of("default", "otro-proyecto"));

        mockMvc.perform(get("/api/admin/proyectos"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("otro-proyecto")));
    }

    @Test
    @DisplayName("POST /api/admin/vault/documentos rechaza con 403 cuando la carga esta deshabilitada")
    void subirArchivoRechazaSiCargaDeshabilitada() throws Exception {
        var archivo = new MockMultipartFile("archivo", "nuevo.md", "text/markdown", "contenido".getBytes());

        mockMvc.perform(multipart("/api/admin/vault/documentos").file(archivo))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/vault/archivos deriva el estado efectivo a partir de los conteos de chunks")
    void archivosVaultDerivaElEstadoEfectivo() throws Exception {
        var enError = new IngestaRepositorio.ArchivoVaultAdmin(
                1L, 10L, "local_docs", "documentos", "roto.pdf", "error", "docling-serve no responde",
                123L, Instant.EPOCH, Instant.EPOCH, 0, 0);
        var embebiendo = new IngestaRepositorio.ArchivoVaultAdmin(
                2L, 10L, "local_docs", "documentos", "guia.md", "procesando", null,
                456L, Instant.EPOCH, Instant.EPOCH, 4, 2);
        var listo = new IngestaRepositorio.ArchivoVaultAdmin(
                3L, 10L, "local_docs", "documentos", "notas.md", "procesando", null,
                789L, Instant.EPOCH, Instant.EPOCH, 3, 3);
        when(repo.listarArchivosVault()).thenReturn(List.of(enError, embebiendo, listo));

        mockMvc.perform(get("/api/admin/vault/archivos"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"estado\":\"error\"")))
                .andExpect(content().string(containsString("\"estado\":\"embebiendo (2/4)\"")))
                .andExpect(content().string(containsString("\"estado\":\"listo\"")));
    }
}
