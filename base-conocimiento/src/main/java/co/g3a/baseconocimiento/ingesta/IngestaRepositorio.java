package co.g3a.baseconocimiento.ingesta;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Todo el SQL de la ingesta en un solo lugar: fuentes, documentos, chunks y la cola de trabajo.
 *
 * <p>Deliberadamente no es genérico ni reutiliza un {@code Repository<T>} abstracto: cada operación
 * existe porque un conector concreto la necesita, y eso es más fácil de leer que una capa de
 * indirección que nadie pidió.
 */
@Repository
class IngestaRepositorio {

  private final JdbcClient jdbc;

  IngestaRepositorio(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  long obtenerOCrearFuente(String kind, String name) {
    return jdbc.sql(
            """
                        INSERT INTO sources (kind, name) VALUES (:kind, :name)
                        ON CONFLICT (kind, name) DO UPDATE SET kind = EXCLUDED.kind
                        RETURNING id
                        """)
        .param("kind", kind)
        .param("name", name)
        .query(Long.class)
        .single();
  }

  record DocumentoExistente(long id, String contentHash) {}

  Optional<DocumentoExistente> buscarDocumento(long sourceId, String externalId) {
    return jdbc.sql(
            """
                        SELECT id, content_hash FROM documents
                        WHERE source_id = :sourceId AND external_id = :externalId
                        """)
        .param("sourceId", sourceId)
        .param("externalId", externalId)
        .query((rs, n) -> new DocumentoExistente(rs.getLong("id"), rs.getString("content_hash")))
        .optional();
  }

  /**
   * Crea o reemplaza el documento por completo. Si ya existía, sus chunks viejos se descartan aquí
   * mismo: la ingesta no deja fragmentos huérfanos de una versión anterior del contenido.
   */
  long upsertDocumento(
      long sourceId,
      String externalId,
      String uri,
      String titulo,
      String rawText,
      String contentHash,
      String projectId) {
    long id =
        jdbc.sql(
                """
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
            .param("sourceId", sourceId)
            .param("externalId", externalId)
            .param("uri", uri)
            .param("titulo", titulo)
            .param("rawText", rawText)
            .param("contentHash", contentHash)
            .param("projectId", projectId)
            .query(Long.class)
            .single();

    jdbc.sql("DELETE FROM chunks WHERE document_id = :id").param("id", id).update();
    return id;
  }

  /**
   * Para {@code ContenidoVaultController}: la uri pedida solo se sirve si de verdad quedó indexada
   * como {@code documents.uri} — sin este chequeo, el controlador serviría cualquier archivo
   * físicamente presente bajo el vault (basura de docling, archivos no aceptados, etc.), no solo
   * los que ya son parte de una cita.
   */
  boolean existeDocumentoConUri(String uri) {
    return jdbc.sql("SELECT 1 FROM documents WHERE uri = :uri")
        .param("uri", uri)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /**
   * @param distilledJson JSON ya serializado; ver {@code chunks.distilled} en el esquema
   */
  long insertarChunk(
      long documentId,
      long sourceId,
      String projectId,
      int ord,
      String kind,
      String texto,
      String distilledJson) {
    return jdbc.sql(
            """
                        INSERT INTO chunks
                            (document_id, source_id, project_id, ord, kind, text, distilled)
                        VALUES (:documentId, :sourceId, :projectId, :ord, :kind, :texto, CAST(:distilled AS jsonb))
                        RETURNING id
                        """)
        .param("documentId", documentId)
        .param("sourceId", sourceId)
        .param("projectId", projectId)
        .param("ord", ord)
        .param("kind", kind)
        .param("texto", texto)
        .param("distilled", distilledJson)
        .query(Long.class)
        .single();
  }

  void encolarEmbeberChunk(long chunkId) {
    jdbc.sql(
            """
                        INSERT INTO ingest_jobs (kind, payload)
                        VALUES ('embeber_chunk', jsonb_build_object('chunk_id', :chunkId))
                        """)
        .param("chunkId", chunkId)
        .update();
  }

  record Trabajo(long id, String kind, String payload, int attempts) {}

  /**
   * Toma el siguiente trabajo pendiente y lo marca {@code running} en la misma transacción del
   * llamador. {@code FOR UPDATE SKIP LOCKED} es lo que permite varios workers sin coordinarse: cada
   * uno se salta lo que otro ya tomó, en vez de bloquearse esperando.
   */
  Optional<Trabajo> tomarSiguienteTrabajo() {
    var trabajo =
        jdbc.sql(
                """
                        SELECT id, kind, payload::text AS payload, attempts FROM ingest_jobs
                        WHERE status = 'pending' AND run_after <= now()
                        ORDER BY id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                        """)
            .query(
                (rs, n) ->
                    new Trabajo(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        rs.getString("payload"),
                        rs.getInt("attempts")))
            .optional();

    trabajo.ifPresent(
        t ->
            jdbc.sql(
                    """
                        UPDATE ingest_jobs SET status = 'running', locked_at = now(), updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", t.id())
                .update());

    return trabajo;
  }

  void marcarHecho(long trabajoId) {
    jdbc.sql("UPDATE ingest_jobs SET status = 'done', updated_at = now() WHERE id = :id")
        .param("id", trabajoId)
        .update();
  }

  /** Reintento con backoff simple; tras {@code maxIntentos} el trabajo queda {@code failed}. */
  void marcarFallo(long trabajoId, int intentosPrevios, int maxIntentos, String error) {
    int intentos = intentosPrevios + 1;
    String estado = intentos >= maxIntentos ? "failed" : "pending";
    int segundosEspera = (int) Math.min(300, Math.pow(2, intentos));

    jdbc.sql(
            """
                        UPDATE ingest_jobs SET
                            status = :estado, attempts = :intentos, last_error = :error,
                            run_after = now() + make_interval(secs => :espera), updated_at = now()
                        WHERE id = :id
                        """)
        .param("estado", estado)
        .param("intentos", intentos)
        .param("error", error)
        .param("espera", segundosEspera)
        .param("id", trabajoId)
        .update();
  }

  record ChunkPendiente(long id, String texto, String distilledJson) {}

  ChunkPendiente obtenerChunk(long chunkId) {
    return jdbc.sql("SELECT id, text, distilled::text AS distilled FROM chunks WHERE id = :id")
        .param("id", chunkId)
        .query(
            (rs, n) ->
                new ChunkPendiente(
                    rs.getLong("id"), rs.getString("text"), rs.getString("distilled")))
        .single();
  }

  void actualizarEmbedding(long chunkId, com.pgvector.PGvector vector) {
    jdbc.sql("UPDATE chunks SET embedding = :embedding WHERE id = :id")
        .param("embedding", vector)
        .param("id", chunkId)
        .update();
  }

  /**
   * Estado incremental de la fuente (ultimo SHA de un repo, delta link de Graph...): ver el
   * comentario de {@code sources.sync_state} en el esquema. Nunca es {@code null} en la fila
   * (default {@code '{}'::jsonb}).
   */
  Optional<String> obtenerSyncState(long sourceId) {
    return jdbc.sql("SELECT sync_state::text FROM sources WHERE id = :id")
        .param("id", sourceId)
        .query(String.class)
        .optional();
  }

  void actualizarSyncState(long sourceId, String syncStateJson) {
    jdbc.sql(
            "UPDATE sources SET sync_state = CAST(:s AS jsonb), last_synced_at = now() WHERE id = :id")
        .param("s", syncStateJson)
        .param("id", sourceId)
        .update();
  }

  /**
   * IDF maximo entre los lexemas de {@code texto}, o 0 si ninguno aparece en {@code term_stats}.
   * Alimenta el gate de bursting de F6 (IDF >= 4.0): mismo mecanismo que la señal 3 de recuperacion
   * (ver el hallazgo de F2 sobre el stemmer) -- ts_stat sobre el propio texto, sin re-estemizar un
   * lexema que term_stats ya guarda estemizado.
   */
  double maxIdf(String texto) {
    return jdbc.sql(
            """
                        WITH lexemas AS (
                            SELECT word FROM ts_stat(
                                'SELECT to_tsvector(''spanish'', ' || quote_literal(:texto) || ')'
                            )
                        )
                        SELECT COALESCE(MAX(ts.idf), 0)
                        FROM term_stats ts JOIN lexemas l ON l.word = ts.term
                        """)
        .param("texto", texto)
        .query(Double.class)
        .single();
  }

  /**
   * Documentos de una fuente cuyo hash ya no coincide con ninguno en {@code vistos}: hay que
   * borrarlos.
   */
  List<Long> documentosHuerfanos(long sourceId, List<String> externalIdsVistos) {
    if (externalIdsVistos.isEmpty()) {
      return jdbc.sql("SELECT id FROM documents WHERE source_id = :sourceId")
          .param("sourceId", sourceId)
          .query(Long.class)
          .list();
    }
    return jdbc.sql(
            """
                        SELECT id FROM documents
                        WHERE source_id = :sourceId AND external_id NOT IN (:vistos)
                        """)
        .param("sourceId", sourceId)
        .param("vistos", externalIdsVistos)
        .query(Long.class)
        .list();
  }

  void eliminarDocumento(long documentId) {
    jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", documentId).update();
  }

  /**
   * Lo que hace falta para borrar un archivo del vault desde la consola: su fuente, su tipo y el
   * documento que lo indexa (si ya llegó a existir).
   */
  record ArchivoVaultParaEliminar(
      long id, long sourceId, String kind, String externalId, Long documentId) {}

  Optional<ArchivoVaultParaEliminar> buscarArchivoVaultParaEliminar(long id) {
    return jdbc.sql(
            """
                        SELECT va.id, va.source_id, s.kind, va.external_id, va.document_id
                        FROM vault_archivos va
                        JOIN sources s ON s.id = va.source_id
                        WHERE va.id = :id
                        """)
        .param("id", id)
        .query(
            (rs, n) ->
                new ArchivoVaultParaEliminar(
                    rs.getLong("id"),
                    rs.getLong("source_id"),
                    rs.getString("kind"),
                    rs.getString("external_id"),
                    rs.getObject("document_id") == null ? null : rs.getLong("document_id")))
        .optional();
  }

  void eliminarArchivoVault(long id) {
    jdbc.sql("DELETE FROM vault_archivos WHERE id = :id").param("id", id).update();
  }

  /**
   * Tarea async de docling-serve en curso para un archivo (ADR-0010): si {@code kb-api} se reinicia
   * mientras la conversión sigue en vuelo, el próximo intento la retoma en vez de mandar una tarea
   * duplicada. Vive por {@code (source_id, external_id)} porque el documento todavía no existe en
   * {@code documents} mientras la conversión está en curso.
   */
  Optional<String> buscarTareaDoclingEnCurso(long sourceId, String externalId) {
    return jdbc.sql(
            """
                        SELECT task_id FROM docling_tareas_en_curso
                        WHERE source_id = :sourceId AND external_id = :externalId
                        """)
        .param("sourceId", sourceId)
        .param("externalId", externalId)
        .query(String.class)
        .optional();
  }

  void registrarTareaDoclingEnCurso(long sourceId, String externalId, String taskId) {
    jdbc.sql(
            """
                        INSERT INTO docling_tareas_en_curso (source_id, external_id, task_id)
                        VALUES (:sourceId, :externalId, :taskId)
                        ON CONFLICT (source_id, external_id) DO UPDATE SET
                            task_id = EXCLUDED.task_id, iniciado_en = now()
                        """)
        .param("sourceId", sourceId)
        .param("externalId", externalId)
        .param("taskId", taskId)
        .update();
  }

  void borrarTareaDoclingEnCurso(long sourceId, String externalId) {
    jdbc.sql(
            """
                        DELETE FROM docling_tareas_en_curso
                        WHERE source_id = :sourceId AND external_id = :externalId
                        """)
        .param("sourceId", sourceId)
        .param("externalId", externalId)
        .update();
  }

  /** Una fila de {@code sources} con sus conteos, para la consola de administración de F9. */
  record FuenteAdmin(
      long id,
      String kind,
      String name,
      String projectId,
      boolean enabled,
      java.time.Instant lastSyncedAt,
      int refreshSeconds,
      long documentos,
      long chunks) {}

  List<FuenteAdmin> listarFuentes() {
    return jdbc.sql(
            """
                        SELECT s.id, s.kind, s.name, s.project_id, s.enabled,
                               s.last_synced_at, s.refresh_seconds,
                               (SELECT COUNT(*) FROM documents d WHERE d.source_id = s.id) AS documentos,
                               (SELECT COUNT(*) FROM chunks c WHERE c.source_id = s.id) AS chunks
                        FROM sources s
                        ORDER BY s.kind, s.name
                        """)
        .query(
            (rs, n) ->
                new FuenteAdmin(
                    rs.getLong("id"),
                    rs.getString("kind"),
                    rs.getString("name"),
                    rs.getString("project_id"),
                    rs.getBoolean("enabled"),
                    rs.getTimestamp("last_synced_at") == null
                        ? null
                        : rs.getTimestamp("last_synced_at").toInstant(),
                    rs.getInt("refresh_seconds"),
                    rs.getLong("documentos"),
                    rs.getLong("chunks")))
        .list();
  }

  /**
   * Los {@code project_id} que existen de verdad, para poblar el selector de proyecto de F10. No
   * alcanza con mirar {@code sources}: una sola fuente de documentos locales (una fila) puede
   * repartir sus archivos en varios proyectos según la subcarpeta (ver {@code
   * ConectorDocumentosLocales.proyectoDe}), así que el proyecto real vive en {@code documents}, no
   * en la fuente que los trajo.
   */
  List<String> listarProyectos() {
    return jdbc.sql(
            """
                        SELECT project_id FROM sources
                        UNION
                        SELECT project_id FROM documents
                        ORDER BY project_id
                        """)
        .query(String.class)
        .list();
  }

  /** Un documento local, para el selector de "documentos activos por conversación" de la UI web. */
  record DocumentoResumen(long id, String titulo) {}

  /**
   * Solo {@code local_docs}: es el único tipo de fuente donde "documento" es un archivo puntual que
   * tiene sentido prender/apagar desde la consola — repos Git, Teams y Azure DevOps se sincronizan
   * solos (mismo criterio que {@code AdminController.eliminarArchivo}).
   */
  List<DocumentoResumen> listarDocumentosLocales(String projectId) {
    return jdbc.sql(
            """
                        SELECT d.id, d.title
                        FROM documents d
                        JOIN sources s ON s.id = d.source_id
                        WHERE s.kind = 'local_docs' AND d.project_id = :projectId
                        ORDER BY d.title
                        """)
        .param("projectId", projectId)
        .query((rs, n) -> new DocumentoResumen(rs.getLong("id"), rs.getString("title")))
        .list();
  }

  record ConteoEstado(String estado, long total) {}

  /** Cuántos trabajos de la cola hay en cada estado (pending/running/done/failed). */
  List<ConteoEstado> contarTrabajosPorEstado() {
    return jdbc.sql("SELECT status, COUNT(*) AS total FROM ingest_jobs GROUP BY status")
        .query((rs, n) -> new ConteoEstado(rs.getString("status"), rs.getLong("total")))
        .list();
  }

  /**
   * Registra que un archivo del vault sigue estando ahí: crea la fila si es la primera vez que se
   * ve, o solo refresca {@code tamano_bytes}/ {@code detectado_en} si ya existía — a propósito NO
   * toca {@code estado} ni {@code actualizado_en} aquí, para que esa columna siga reflejando el
   * último cambio de estado real (y no cada corrida del relevo) al ordenar el panel de
   * administración.
   */
  void marcarArchivoDetectado(long sourceId, String externalId, long tamanoBytes) {
    jdbc.sql(
            """
                        INSERT INTO vault_archivos (source_id, external_id, tamano_bytes)
                        VALUES (:sourceId, :externalId, :tamanoBytes)
                        ON CONFLICT (source_id, external_id) DO UPDATE SET
                            tamano_bytes = EXCLUDED.tamano_bytes, detectado_en = now()
                        """)
        .param("sourceId", sourceId)
        .param("externalId", externalId)
        .param("tamanoBytes", tamanoBytes)
        .update();
  }

  void marcarArchivoExtrayendo(long sourceId, String externalId) {
    jdbc.sql(
            """
                        UPDATE vault_archivos SET estado = 'extrayendo', actualizado_en = now()
                        WHERE source_id = :sourceId AND external_id = :externalId
                        """)
        .param("sourceId", sourceId)
        .param("externalId", externalId)
        .update();
  }

  void marcarArchivoProcesado(long sourceId, String externalId, long documentId) {
    jdbc.sql(
            """
                        UPDATE vault_archivos SET
                            estado = 'procesando', document_id = :documentId,
                            last_error = NULL, actualizado_en = now()
                        WHERE source_id = :sourceId AND external_id = :externalId
                        """)
        .param("sourceId", sourceId)
        .param("externalId", externalId)
        .param("documentId", documentId)
        .update();
  }

  void marcarArchivoError(long sourceId, String externalId, String mensaje) {
    jdbc.sql(
            """
                        UPDATE vault_archivos SET estado = 'error', last_error = :mensaje, actualizado_en = now()
                        WHERE source_id = :sourceId AND external_id = :externalId
                        """)
        .param("sourceId", sourceId)
        .param("externalId", externalId)
        .param("mensaje", mensaje)
        .update();
  }

  /** Igual que {@link #documentosHuerfanos}, pero para las filas de {@code vault_archivos}. */
  void eliminarArchivosVaultHuerfanos(long sourceId, List<String> externalIdsVistos) {
    if (externalIdsVistos.isEmpty()) {
      jdbc.sql("DELETE FROM vault_archivos WHERE source_id = :sourceId")
          .param("sourceId", sourceId)
          .update();
      return;
    }
    jdbc.sql(
            """
                        DELETE FROM vault_archivos
                        WHERE source_id = :sourceId AND external_id NOT IN (:vistos)
                        """)
        .param("sourceId", sourceId)
        .param("vistos", externalIdsVistos)
        .update();
  }

  /**
   * Una fila por archivo del vault, con los conteos de chunks necesarios para que el llamador
   * derive el estado efectivo (p. ej. "embebiendo 3/12"): ver {@code vault_archivos.estado} en la
   * migración V3 sobre por qué esto no se guarda como columna.
   */
  record ArchivoVaultAdmin(
      long id,
      long sourceId,
      String kind,
      String fuenteNombre,
      String externalId,
      String estado,
      String lastError,
      long tamanoBytes,
      java.time.Instant detectadoEn,
      java.time.Instant actualizadoEn,
      long chunksTotales,
      long chunksEmbebidos) {}

  List<ArchivoVaultAdmin> listarArchivosVault() {
    return jdbc.sql(
            """
                        SELECT va.id, va.source_id, s.kind, s.name AS fuente_nombre, va.external_id,
                               va.estado, va.last_error, va.tamano_bytes, va.detectado_en, va.actualizado_en,
                               COUNT(c.id) AS chunks_totales,
                               COUNT(c.id) FILTER (WHERE c.embedding IS NOT NULL) AS chunks_embebidos
                        FROM vault_archivos va
                        JOIN sources s ON s.id = va.source_id
                        LEFT JOIN chunks c ON c.document_id = va.document_id
                        GROUP BY va.id, s.kind, s.name
                        ORDER BY va.actualizado_en DESC
                        """)
        .query(
            (rs, n) ->
                new ArchivoVaultAdmin(
                    rs.getLong("id"),
                    rs.getLong("source_id"),
                    rs.getString("kind"),
                    rs.getString("fuente_nombre"),
                    rs.getString("external_id"),
                    rs.getString("estado"),
                    rs.getString("last_error"),
                    rs.getLong("tamano_bytes"),
                    rs.getTimestamp("detectado_en").toInstant(),
                    rs.getTimestamp("actualizado_en").toInstant(),
                    rs.getLong("chunks_totales"),
                    rs.getLong("chunks_embebidos")))
        .list();
  }
}
