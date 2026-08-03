package co.g3a.baseconocimiento.ingesta;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Todo el SQL de la ingesta en un solo lugar: fuentes, documentos, chunks y la
 * cola de trabajo.
 *
 * <p>Deliberadamente no es genérico ni reutiliza un {@code Repository<T>}
 * abstracto: cada operación existe porque un conector concreto la necesita, y
 * eso es más fácil de leer que una capa de indirección que nadie pidió.
 */
@Repository
class IngestaRepositorio {

    private final JdbcClient jdbc;

    IngestaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long obtenerOCrearFuente(String kind, String name) {
        return jdbc.sql("""
                        INSERT INTO sources (kind, name) VALUES (:kind, :name)
                        ON CONFLICT (kind, name) DO UPDATE SET kind = EXCLUDED.kind
                        RETURNING id
                        """)
                .param("kind", kind).param("name", name)
                .query(Long.class).single();
    }

    record DocumentoExistente(long id, String contentHash) {
    }

    Optional<DocumentoExistente> buscarDocumento(long sourceId, String externalId) {
        return jdbc.sql("""
                        SELECT id, content_hash FROM documents
                        WHERE source_id = :sourceId AND external_id = :externalId
                        """)
                .param("sourceId", sourceId).param("externalId", externalId)
                .query((rs, n) -> new DocumentoExistente(rs.getLong("id"), rs.getString("content_hash")))
                .optional();
    }

    /**
     * Crea o reemplaza el documento por completo. Si ya existía, sus chunks
     * viejos se descartan aquí mismo: la ingesta no deja fragmentos huérfanos
     * de una versión anterior del contenido.
     */
    long upsertDocumento(long sourceId, String externalId, String uri, String titulo,
            String rawText, String contentHash, String projectId) {
        long id = jdbc.sql("""
                        INSERT INTO documents
                            (source_id, external_id, uri, title, raw_text, content_hash, project_id)
                        VALUES (:sourceId, :externalId, :uri, :titulo, :rawText, :contentHash, :projectId)
                        ON CONFLICT (source_id, external_id) DO UPDATE SET
                            uri = EXCLUDED.uri,
                            title = EXCLUDED.title,
                            raw_text = EXCLUDED.raw_text,
                            content_hash = EXCLUDED.content_hash,
                            source_updated_at = now()
                        RETURNING id
                        """)
                .param("sourceId", sourceId).param("externalId", externalId).param("uri", uri)
                .param("titulo", titulo).param("rawText", rawText).param("contentHash", contentHash)
                .param("projectId", projectId)
                .query(Long.class).single();

        jdbc.sql("DELETE FROM chunks WHERE document_id = :id").param("id", id).update();
        return id;
    }

    /** @param distilledJson JSON ya serializado; ver {@code chunks.distilled} en el esquema */
    long insertarChunk(long documentId, long sourceId, String projectId, int ord, String kind,
            String texto, String distilledJson) {
        return jdbc.sql("""
                        INSERT INTO chunks
                            (document_id, source_id, project_id, ord, kind, text, distilled)
                        VALUES (:documentId, :sourceId, :projectId, :ord, :kind, :texto, CAST(:distilled AS jsonb))
                        RETURNING id
                        """)
                .param("documentId", documentId).param("sourceId", sourceId).param("projectId", projectId)
                .param("ord", ord).param("kind", kind).param("texto", texto).param("distilled", distilledJson)
                .query(Long.class).single();
    }

    void encolarEmbeberChunk(long chunkId) {
        jdbc.sql("""
                        INSERT INTO ingest_jobs (kind, payload)
                        VALUES ('embeber_chunk', jsonb_build_object('chunk_id', :chunkId))
                        """)
                .param("chunkId", chunkId).update();
    }

    record Trabajo(long id, String kind, String payload, int attempts) {
    }

    /**
     * Toma el siguiente trabajo pendiente y lo marca {@code running} en la misma
     * transacción del llamador. {@code FOR UPDATE SKIP LOCKED} es lo que permite
     * varios workers sin coordinarse: cada uno se salta lo que otro ya tomó, en
     * vez de bloquearse esperando.
     */
    Optional<Trabajo> tomarSiguienteTrabajo() {
        var trabajo = jdbc.sql("""
                        SELECT id, kind, payload::text AS payload, attempts FROM ingest_jobs
                        WHERE status = 'pending' AND run_after <= now()
                        ORDER BY id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                        """)
                .query((rs, n) -> new Trabajo(
                        rs.getLong("id"), rs.getString("kind"), rs.getString("payload"), rs.getInt("attempts")))
                .optional();

        trabajo.ifPresent(t -> jdbc.sql("""
                        UPDATE ingest_jobs SET status = 'running', locked_at = now(), updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", t.id()).update());

        return trabajo;
    }

    void marcarHecho(long trabajoId) {
        jdbc.sql("UPDATE ingest_jobs SET status = 'done', updated_at = now() WHERE id = :id")
                .param("id", trabajoId).update();
    }

    /** Reintento con backoff simple; tras {@code maxIntentos} el trabajo queda {@code failed}. */
    void marcarFallo(long trabajoId, int intentosPrevios, int maxIntentos, String error) {
        int intentos = intentosPrevios + 1;
        String estado = intentos >= maxIntentos ? "failed" : "pending";
        int segundosEspera = (int) Math.min(300, Math.pow(2, intentos));

        jdbc.sql("""
                        UPDATE ingest_jobs SET
                            status = :estado, attempts = :intentos, last_error = :error,
                            run_after = now() + make_interval(secs => :espera), updated_at = now()
                        WHERE id = :id
                        """)
                .param("estado", estado).param("intentos", intentos)
                .param("error", error).param("espera", segundosEspera).param("id", trabajoId)
                .update();
    }

    record ChunkPendiente(long id, String texto, String distilledJson) {
    }

    ChunkPendiente obtenerChunk(long chunkId) {
        return jdbc.sql("SELECT id, text, distilled::text AS distilled FROM chunks WHERE id = :id")
                .param("id", chunkId)
                .query((rs, n) -> new ChunkPendiente(rs.getLong("id"), rs.getString("text"), rs.getString("distilled")))
                .single();
    }

    void actualizarEmbedding(long chunkId, com.pgvector.PGvector vector) {
        jdbc.sql("UPDATE chunks SET embedding = :embedding WHERE id = :id")
                .param("embedding", vector).param("id", chunkId)
                .update();
    }

    /**
     * Estado incremental de la fuente (ultimo SHA de un repo, delta link de
     * Graph...): ver el comentario de {@code sources.sync_state} en el
     * esquema. Nunca es {@code null} en la fila (default {@code '{}'::jsonb}).
     */
    Optional<String> obtenerSyncState(long sourceId) {
        return jdbc.sql("SELECT sync_state::text FROM sources WHERE id = :id")
                .param("id", sourceId).query(String.class).optional();
    }

    void actualizarSyncState(long sourceId, String syncStateJson) {
        jdbc.sql("UPDATE sources SET sync_state = CAST(:s AS jsonb), last_synced_at = now() WHERE id = :id")
                .param("s", syncStateJson).param("id", sourceId).update();
    }

    /**
     * IDF maximo entre los lexemas de {@code texto}, o 0 si ninguno aparece en
     * {@code term_stats}. Alimenta el gate de bursting de F6 (IDF >= 4.0):
     * mismo mecanismo que la señal 3 de recuperacion (ver el hallazgo de F2
     * sobre el stemmer) -- ts_stat sobre el propio texto, sin re-estemizar un
     * lexema que term_stats ya guarda estemizado.
     */
    double maxIdf(String texto) {
        return jdbc.sql("""
                        WITH lexemas AS (
                            SELECT word FROM ts_stat(
                                'SELECT to_tsvector(''spanish'', ' || quote_literal(:texto) || ')'
                            )
                        )
                        SELECT COALESCE(MAX(ts.idf), 0)
                        FROM term_stats ts JOIN lexemas l ON l.word = ts.term
                        """)
                .param("texto", texto)
                .query(Double.class).single();
    }

    /** Documentos de una fuente cuyo hash ya no coincide con ninguno en {@code vistos}: hay que borrarlos. */
    List<Long> documentosHuerfanos(long sourceId, List<String> externalIdsVistos) {
        if (externalIdsVistos.isEmpty()) {
            return jdbc.sql("SELECT id FROM documents WHERE source_id = :sourceId")
                    .param("sourceId", sourceId).query(Long.class).list();
        }
        return jdbc.sql("""
                        SELECT id FROM documents
                        WHERE source_id = :sourceId AND external_id NOT IN (:vistos)
                        """)
                .param("sourceId", sourceId).param("vistos", externalIdsVistos)
                .query(Long.class).list();
    }

    void eliminarDocumento(long documentId) {
        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", documentId).update();
    }

    /** Una fila de {@code sources} con sus conteos, para la consola de administración de F9. */
    record FuenteAdmin(
            long id, String kind, String name, String projectId, boolean enabled,
            java.time.Instant lastSyncedAt, int refreshSeconds, long documentos, long chunks) {
    }

    List<FuenteAdmin> listarFuentes() {
        return jdbc.sql("""
                        SELECT s.id, s.kind, s.name, s.project_id, s.enabled,
                               s.last_synced_at, s.refresh_seconds,
                               (SELECT COUNT(*) FROM documents d WHERE d.source_id = s.id) AS documentos,
                               (SELECT COUNT(*) FROM chunks c WHERE c.source_id = s.id) AS chunks
                        FROM sources s
                        ORDER BY s.kind, s.name
                        """)
                .query((rs, n) -> new FuenteAdmin(
                        rs.getLong("id"), rs.getString("kind"), rs.getString("name"),
                        rs.getString("project_id"), rs.getBoolean("enabled"),
                        rs.getTimestamp("last_synced_at") == null ? null : rs.getTimestamp("last_synced_at").toInstant(),
                        rs.getInt("refresh_seconds"), rs.getLong("documentos"), rs.getLong("chunks")))
                .list();
    }

    /** Los {@code project_id} que existen de verdad, para poblar el selector de proyecto de F10. */
    List<String> listarProyectos() {
        return jdbc.sql("SELECT DISTINCT project_id FROM sources ORDER BY project_id")
                .query(String.class).list();
    }

    record ConteoEstado(String estado, long total) {
    }

    /** Cuántos trabajos de la cola hay en cada estado (pending/running/done/failed). */
    List<ConteoEstado> contarTrabajosPorEstado() {
        return jdbc.sql("SELECT status, COUNT(*) AS total FROM ingest_jobs GROUP BY status")
                .query((rs, n) -> new ConteoEstado(rs.getString("status"), rs.getLong("total")))
                .list();
    }
}
