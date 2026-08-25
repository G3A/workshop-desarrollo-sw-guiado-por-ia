-- Base de Conocimiento — esquema inicial.
--
-- Decision central, tomada del articulo de Cerebras: TODAS las fuentes caen en la
-- MISMA tabla `chunks`. Documentos, codigo, hilos de Teams y work items comparten
-- columna de embedding, columna FTS e indices. Una sola interfaz de consulta.
--
-- Segunda decision: el texto crudo NUNCA entra al espacio vectorial. El embedding
-- ancla en los campos destilados por el LLM; el crudo solo alimenta FTS.

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- sources — una fila por fuente configurada.
-- Cada una lleva su propia cadencia de refresco: frescura por fuente, sin cron global.
-- ---------------------------------------------------------------------------
CREATE TABLE sources (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind            TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    project_id      TEXT        NOT NULL DEFAULT 'default',
    config          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    refresh_seconds INTEGER     NOT NULL DEFAULT 900,
    sync_state      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sources_kind_valido CHECK (
        kind IN ('local_docs', 'local_git', 'teams_channel', 'azure_devops')
    ),
    CONSTRAINT sources_nombre_unico UNIQUE (kind, name)
);

COMMENT ON COLUMN sources.sync_state IS
    'Estado incremental especifico de la fuente: ultimo SHA por repo, delta link de Graph, etc.';

-- ---------------------------------------------------------------------------
-- documents — la unidad que el usuario reconoce: un archivo, un hilo, un work item.
-- ---------------------------------------------------------------------------
CREATE TABLE documents (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id         BIGINT      NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    external_id       TEXT        NOT NULL,
    uri               TEXT        NOT NULL,
    title             TEXT,
    raw_text          TEXT,
    metadata          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    project_id        TEXT        NOT NULL DEFAULT 'default',
    acl               JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Hash del contenido: si no cambio, no se vuelve a embeber. Es lo que hace
    -- barata la re-ingesta incremental.
    content_hash      TEXT        NOT NULL,
    source_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ingested_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT documents_externo_unico UNIQUE (source_id, external_id)
);

CREATE INDEX documents_source_project_idx ON documents (source_id, project_id);
CREATE INDEX documents_actualizado_idx    ON documents (source_updated_at DESC);

-- ---------------------------------------------------------------------------
-- chunks — la tabla unica de embeddings. Aqui converge todo.
-- ---------------------------------------------------------------------------
CREATE TABLE chunks (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id       BIGINT      NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    source_id         BIGINT      NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    project_id        TEXT        NOT NULL DEFAULT 'default',
    ord               INTEGER     NOT NULL,
    kind              TEXT        NOT NULL,
    text              TEXT        NOT NULL,

    -- Destilacion por LLM. El embedding ancla aqui, no en `text`.
    -- Campos: searchable_question, summary, resolution, systems_mentioned, code_references.
    distilled         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    metadata          JSONB       NOT NULL DEFAULT '{}'::jsonb,

    -- 1024 dimensiones = bge-m3. Cambiar de modelo de embeddings exige migracion nueva.
    embedding         VECTOR(1024),

    -- Senal 1 de 4. Pesos: la pregunta destilada manda sobre el resumen, y el
    -- resumen sobre el texto crudo. El crudo esta aqui y SOLO aqui.
    fts               TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('spanish', coalesce(distilled ->> 'searchable_question', '')), 'A') ||
        setweight(to_tsvector('spanish', coalesce(distilled ->> 'summary', '')), 'B') ||
        setweight(to_tsvector('spanish', coalesce(distilled ->> 'resolution', '')), 'B') ||
        setweight(to_tsvector('spanish', coalesce(text, '')), 'C')
    ) STORED,

    source_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chunks_kind_valido CHECK (
        kind IN ('doc_section', 'code_block', 'thread', 'thread_burst', 'work_item', 'wiki_section')
    ),
    CONSTRAINT chunks_orden_unico UNIQUE (document_id, ord)
);

-- Senal 2: busqueda densa. HNSW con distancia coseno.
CREATE INDEX chunks_embedding_hnsw_idx ON chunks
    USING hnsw (embedding vector_cosine_ops);

-- Senal 1: texto completo.
CREATE INDEX chunks_fts_gin_idx ON chunks USING gin (fts);

-- Senal 4: decaimiento por antiguedad, y filtrado por proyecto antes del planner.
CREATE INDEX chunks_actualizado_idx     ON chunks (source_updated_at DESC);
CREATE INDEX chunks_source_project_idx  ON chunks (source_id, project_id);
CREATE INDEX chunks_document_idx        ON chunks (document_id);

-- ---------------------------------------------------------------------------
-- term_stats — frecuencia documental del corpus.
-- Alimenta la senal 3 (supresion de relleno por IDF) y el gate de bursting
-- (IDF >= 4.0) durante la ingesta. Se recalcula por lote, no en cada consulta.
-- ---------------------------------------------------------------------------
CREATE TABLE term_stats (
    term         TEXT   PRIMARY KEY,
    df           BIGINT NOT NULL,
    idf          REAL   NOT NULL,
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX term_stats_idf_idx ON term_stats (idf DESC);

-- ---------------------------------------------------------------------------
-- ingest_jobs — la cola. Vive en Postgres para no arrastrar un Redis.
-- El worker toma trabajo con SELECT ... FOR UPDATE SKIP LOCKED.
-- ---------------------------------------------------------------------------
CREATE TABLE ingest_jobs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id     BIGINT      REFERENCES sources (id) ON DELETE CASCADE,
    kind          TEXT        NOT NULL,
    payload       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status        TEXT        NOT NULL DEFAULT 'pending',
    attempts      INTEGER     NOT NULL DEFAULT 0,
    last_error    TEXT,
    run_after     TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ingest_jobs_status_valido CHECK (
        status IN ('pending', 'running', 'done', 'failed')
    )
);

-- Indice parcial: el worker solo mira lo pendiente y vencido.
CREATE INDEX ingest_jobs_pendientes_idx ON ingest_jobs (run_after)
    WHERE status = 'pending';

-- ---------------------------------------------------------------------------
-- query_log — auditoria de cada consulta.
-- El articulo lo señala explicitamente: sin esto no se depuran respuestas malas
-- a escala. Guarda el plan, las herramientas ejecutadas y los candidatos.
-- ---------------------------------------------------------------------------
CREATE TABLE query_log (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question       TEXT        NOT NULL,
    project_id     TEXT        NOT NULL DEFAULT 'default',
    adapter        TEXT        NOT NULL,
    plan           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    tools_run      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    candidates     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    answer         TEXT,
    citations      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    latency_ms     INTEGER,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT query_log_adapter_valido CHECK (adapter IN ('web', 'teams'))
);

CREATE INDEX query_log_creado_idx ON query_log (created_at DESC);
