package co.g3a.baseconocimiento;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifica el esquema contra un PostgreSQL real.
 *
 * <p>Este test nace de un fallo silencioso encontrado en F0: en Spring Boot 4 las
 * autoconfiguraciones se partieron en modulos, y tener {@code flyway-core} en el
 * classpath ya no basta para que Flyway corra. La aplicacion arrancaba contra una
 * base vacia, reportaba salud UP y no decia nada. Lo unico que evita que eso
 * vuelva a pasar es una asercion que lo compruebe en cada build.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Este test no levanta Ollama; sin apagar el worker, @Scheduled intenta
        // embeber igual cada pocos segundos y ensucia el log con errores de
        // conexion que no tienen nada que ver con lo que aqui se verifica.
        properties = "kb.ingesta.worker.habilitado=false")
@Testcontainers
class EsquemaTest {

    // En Testcontainers 2.0 PostgreSQLContainer dejo de ser generico: sin <?> ni <>.
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18-trixie")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("Flyway aplico la migracion inicial")
    void flywayCorrio() {
        var aplicadas = jdbc.sql("""
                        SELECT version FROM flyway_schema_history WHERE success = true
                        """)
                .query(String.class).list();

        assertThat(aplicadas)
                .as("si esto falla, revisa que `spring-boot-flyway` siga en el pom: "
                        + "sin ese modulo la app arranca contra una base vacia sin avisar")
                .contains("1");
    }

    @Test
    @DisplayName("Existen las cinco tablas del diseno")
    void tablasCreadas() {
        var tablas = jdbc.sql("""
                        SELECT tablename FROM pg_tables WHERE schemaname = 'public'
                        """)
                .query(String.class).list();

        assertThat(tablas).contains(
                "sources", "documents", "chunks", "term_stats", "ingest_jobs", "query_log");
    }

    @Test
    @DisplayName("Los indices que sostienen las senales 1 y 2 existen y son del tipo correcto")
    void indicesDeRecuperacion() {
        record Indice(String nombre, String metodo) {
        }

        List<Indice> indices = jdbc.sql("""
                        SELECT i.relname AS nombre, am.amname AS metodo
                        FROM pg_index x
                        JOIN pg_class i ON i.oid = x.indexrelid
                        JOIN pg_class t ON t.oid = x.indrelid
                        JOIN pg_am am   ON am.oid = i.relam
                        WHERE t.relname = 'chunks'
                        """)
                .query((rs, n) -> new Indice(rs.getString("nombre"), rs.getString("metodo")))
                .list();

        assertThat(indices)
                .as("senal 2 (densa): sin HNSW la busqueda vectorial degrada a escaneo secuencial")
                .contains(new Indice("chunks_embedding_hnsw_idx", "hnsw"));

        assertThat(indices)
                .as("senal 1 (texto completo): el GIN es lo que la hace viable")
                .contains(new Indice("chunks_fts_gin_idx", "gin"));
    }

    @Test
    @DisplayName("La destilacion pesa mas que el texto crudo en la senal de texto completo")
    void loDestiladoDominaAlCrudo() {
        // Esta es LA decision de diseno del articulo de Cerebras, expresada como
        // asercion: el embedding y la busqueda anclan en lo que el LLM destilo,
        // y el texto crudo solo participa como red de seguridad, con menos peso.
        insertarChunk("d1",
                "Para desplegar corre docker compose up y luego make pull-models.",
                "{\"searchable_question\":\"como se despliega el servicio\"}");

        Double porDestilado = rank("d1", "como se despliega");
        Double porCrudo = rank("d1", "pull models");

        assertThat(porDestilado)
                .as("la pregunta destilada debe encontrarse")
                .isNotNull();
        assertThat(porCrudo)
                .as("el texto crudo tambien debe encontrarse: es la red de seguridad")
                .isNotNull();
        assertThat(porDestilado)
                .as("pero la destilacion pesa mas; si se igualan, los pesos A/B/C se rompieron")
                .isGreaterThan(porCrudo);
    }

    @Test
    @DisplayName("El indice de texto completo usa la configuracion en espanol, no la inglesa")
    void stemmingEnEspanol() {
        var texto = "El sistema ejecuta las migraciones al arrancar";
        insertarChunk("d2", texto, "{}");

        // El par esta elegido a proposito: "ejecuta" y "ejecutar" comparten raiz
        // bajo la configuracion 'spanish' (ambas dan 'ejecut'), pero NO bajo la
        // inglesa. Si alguien cambia la configuracion de la columna generada en
        // V1__esquema.sql, este test lo detecta.
        //
        // Cuidado con los pares "obvios": 'despliegue' y 'desplegar' NO comparten
        // raiz ('desplieg' contra 'despleg'), y 'servicio'/'servicios' coinciden
        // en ambos idiomas, asi que ninguno de los dos sirve como prueba.
        var coincidencias = jdbc.sql("""
                        SELECT count(*) FROM chunks c, plainto_tsquery('spanish', 'ejecutar') q
                        WHERE c.fts @@ q AND c.document_id = (
                            SELECT id FROM documents WHERE external_id = 'd2')
                        """)
                .query(Long.class).single();

        assertThat(coincidencias)
                .as("la configuracion 'spanish' debe reconocer 'ejecuta' como forma de 'ejecutar'")
                .isEqualTo(1L);

        var coincidiriaEnIngles = jdbc.sql("""
                        SELECT to_tsvector('english', :t) @@ plainto_tsquery('english', 'ejecutar')
                        """)
                .param("t", texto)
                .query(Boolean.class).single();

        assertThat(coincidiriaEnIngles)
                .as("si esto fuera true el par no probaria nada: la prueba perderia sentido")
                .isFalse();
    }

    /** Inserta fuente, documento y chunk enlazados, con identificador propio por prueba. */
    private void insertarChunk(String externalId, String texto, String destiladoJson) {
        jdbc.sql("INSERT INTO sources (kind, name) VALUES ('local_docs', :n)")
                .param("n", externalId).update();
        jdbc.sql("""
                        INSERT INTO documents (source_id, external_id, uri, title, raw_text, content_hash)
                        SELECT id, :e, 'file:///' || :e, :e, 'crudo', :e FROM sources WHERE name = :e
                        """)
                .param("e", externalId).update();
        jdbc.sql("""
                        INSERT INTO chunks (document_id, source_id, ord, kind, text, distilled)
                        SELECT d.id, d.source_id, 0, 'doc_section', :t, CAST(:j AS jsonb)
                        FROM documents d WHERE d.external_id = :e
                        """)
                .param("e", externalId).param("t", texto).param("j", destiladoJson).update();
    }

    /** Puntaje de texto completo, acotado a un documento para que las pruebas no se pisen. */
    private Double rank(String externalId, String consulta) {
        return jdbc.sql("""
                        SELECT max(ts_rank_cd(c.fts, q))
                        FROM chunks c, plainto_tsquery('spanish', :q) q
                        WHERE c.fts @@ q AND c.document_id = (
                            SELECT id FROM documents WHERE external_id = :e)
                        """)
                .param("e", externalId).param("q", consulta)
                .query(Double.class).single();
    }
}
