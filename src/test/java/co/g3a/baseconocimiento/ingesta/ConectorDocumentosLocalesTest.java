package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link ConectorDocumentosLocales#extraerViaDocling}: retoma una tarea de
 * docling-serve ya registrada en vez de someter una duplicada, y reintenta una
 * vez si la tarea heredada ya no existe (ADR-0010, el reinicio de {@code
 * kb-api} a mitad de una conversión larga). {@link ExtractorDocling} queda
 * doblado a propósito: habla con un docling-serve real, y este proyecto no lo
 * levanta en pruebas — ver el ADR.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "kb.ingesta.worker.habilitado=false",
                "kb.recuperacion.terminos.habilitado=false"
        })
@Testcontainers
class ConectorDocumentosLocalesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18-trixie")
                    .asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path vaultRoot;

    static Path documentosDir;

    @BeforeAll
    static void crearCarpetaDocumentos() throws IOException {
        documentosDir = vaultRoot.resolve("documentos");
        Files.createDirectories(documentosDir);
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("kb.ingesta.vault-dir", () -> vaultRoot.toString());
    }

    @Autowired
    ConectorDocumentosLocales conector;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ExtractorDocling extractorDocling;

    @Test
    @DisplayName("Somete una tarea nueva, la registra antes de esperar, y la borra al terminar")
    void someteTareaNuevaYLaRegistraMientrasEsperaElResultado() throws Exception {
        Files.write(documentosDir.resolve("nuevo.pdf"), "contenido".getBytes());
        when(extractorDocling.submitirTarea(eq("nuevo.pdf"), any())).thenReturn("tarea-1");
        when(extractorDocling.esperarYExtraer(eq("nuevo.pdf"), eq("tarea-1"))).thenAnswer(inv -> {
            // Mientras "espera" el resultado, la tarea ya debe estar persistida:
            // es justo la ventana que sobrevive a un reinicio de kb-api.
            assertThat(tareaRegistrada("nuevo.pdf")).contains("tarea-1");
            return "## Titulo\ncontenido";
        });

        var resumen = conector.ingerir();

        assertThat(resumen.documentosActualizados()).isEqualTo(1);
        assertThat(tareaRegistrada("nuevo.pdf")).isEmpty();
    }

    @Test
    @DisplayName("Retoma una tarea ya registrada (reinicio a mitad de camino) en vez de duplicarla")
    void retomaTareaYaRegistradaEnVezDeSometerUnaDuplicada() throws Exception {
        long sourceId = crearFuenteVacia();
        Files.write(documentosDir.resolve("retomado.pdf"), "contenido".getBytes());
        registrarTareaPrevia(sourceId, "retomado.pdf", "tarea-heredada");
        when(extractorDocling.esperarYExtraer(eq("retomado.pdf"), eq("tarea-heredada")))
                .thenReturn("## Titulo\ncontenido");

        var resumen = conector.ingerir();

        assertThat(resumen.documentosActualizados()).isEqualTo(1);
        verify(extractorDocling, never()).submitirTarea(any(), any());
        assertThat(tareaRegistrada("retomado.pdf")).isEmpty();
    }

    @Test
    @DisplayName("Si la tarea heredada ya no existe en docling-serve (404), reintenta una vez con una nueva")
    void reintentaConTareaNuevaSiLaHeredadaYaNoExiste() throws Exception {
        long sourceId = crearFuenteVacia();
        Files.write(documentosDir.resolve("huerfano.pdf"), "contenido".getBytes());
        registrarTareaPrevia(sourceId, "huerfano.pdf", "tarea-huerfana");
        when(extractorDocling.esperarYExtraer(eq("huerfano.pdf"), eq("tarea-huerfana")))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));
        when(extractorDocling.submitirTarea(eq("huerfano.pdf"), any())).thenReturn("tarea-nueva");
        when(extractorDocling.esperarYExtraer(eq("huerfano.pdf"), eq("tarea-nueva")))
                .thenReturn("## Titulo\ncontenido");

        var resumen = conector.ingerir();

        assertThat(resumen.documentosActualizados()).isEqualTo(1);
        assertThat(tareaRegistrada("huerfano.pdf")).isEmpty();
    }

    @Test
    @DisplayName("Si la tarea falla, no queda registrada — el próximo relevo puede reintentar de cero")
    void tareaQueFallaNoDejaRegistroHuerfano() throws Exception {
        Files.write(documentosDir.resolve("fallido.pdf"), "contenido".getBytes());
        when(extractorDocling.submitirTarea(eq("fallido.pdf"), any())).thenReturn("tarea-fallida");
        when(extractorDocling.esperarYExtraer(eq("fallido.pdf"), eq("tarea-fallida")))
                .thenThrow(new IllegalStateException("docling-serve no pudo convertir fallido.pdf"));

        var resumen = conector.ingerir();

        // El archivo problemático no tumba el resto de la corrida (mismo
        // aislamiento de fallos que el resto de ingerir()).
        assertThat(resumen.documentosActualizados()).isEqualTo(0);
        assertThat(tareaRegistrada("fallido.pdf")).isEmpty();

        // Este archivo nunca llega a ingerirse con éxito, así que sin borrarlo
        // el resto de las pruebas de esta clase (mismo directorio temporal
        // compartido) lo reintentarían en cada llamada a ingerir().
        Files.deleteIfExists(documentosDir.resolve("fallido.pdf"));
    }

    @Test
    @DisplayName("Un archivo bajo documentos/<proyecto>/ se ingiere con ese project_id; uno suelto en la raíz, con 'default'")
    void derivaElProyectoDeLaSubcarpeta() throws Exception {
        Files.write(documentosDir.resolve("suelto.md"), "# Suelto\ncontenido".getBytes());
        Path subcarpeta = Files.createDirectories(documentosDir.resolve("ejemplo"));
        Files.write(subcarpeta.resolve("anidado.md"), "# Anidado\ncontenido".getBytes());

        conector.ingerir();

        assertThat(proyectoDeDocumento("suelto.md")).contains("default");
        assertThat(proyectoDeDocumento("ejemplo/anidado.md")).contains("ejemplo");

        Files.deleteIfExists(documentosDir.resolve("suelto.md"));
        Files.deleteIfExists(subcarpeta.resolve("anidado.md"));
        Files.deleteIfExists(subcarpeta);
    }

    private Optional<String> proyectoDeDocumento(String externalId) {
        return jdbc.sql("SELECT project_id FROM documents WHERE external_id = :externalId")
                .param("externalId", externalId)
                .query(String.class).optional();
    }

    /**
     * Inserta (o reutiliza) la fila de {@code sources} sin escanear el
     * vault — a diferencia de llamar a {@code conector.ingerir()}, no
     * reprocesa archivos que hayan quedado de otras pruebas de esta clase en
     * el directorio temporal compartido.
     */
    private long crearFuenteVacia() {
        return jdbc.sql("""
                        INSERT INTO sources (kind, name) VALUES ('local_docs', 'corpus')
                        ON CONFLICT (kind, name) DO UPDATE SET kind = EXCLUDED.kind
                        RETURNING id
                        """)
                .query(Long.class).single();
    }

    private void registrarTareaPrevia(long sourceId, String externalId, String taskId) {
        jdbc.sql("""
                        INSERT INTO docling_tareas_en_curso (source_id, external_id, task_id)
                        VALUES (:sourceId, :externalId, :taskId)
                        """)
                .param("sourceId", sourceId).param("externalId", externalId).param("taskId", taskId)
                .update();
    }

    private Optional<String> tareaRegistrada(String externalId) {
        return jdbc.sql("""
                        SELECT t.task_id FROM docling_tareas_en_curso t
                        JOIN sources s ON s.id = t.source_id
                        WHERE s.kind = 'local_docs' AND t.external_id = :externalId
                        """)
                .param("externalId", externalId)
                .query(String.class).optional();
    }
}
