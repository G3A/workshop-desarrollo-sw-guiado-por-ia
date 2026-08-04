-- Panel de administracion estilo "Job Runner": estado de CADA archivo del
-- vault, no solo el resumen agregado por fuente que ya da `sources`.
--
-- `estado` cubre unicamente las fases que el conector controla de forma
-- directa (detecto el archivo, lo esta extrayendo con Docling, lo proceso con
-- exito, o fallo). El paso final -- "ya se termino de embeber" -- NO se
-- escribe aca: se deriva en la lectura comparando los chunks del documento
-- contra los que ya tienen `embedding IS NOT NULL`, porque el worker de
-- embeddings (TrabajadorEmbebido) no sabe nada de archivos, solo de chunks.
CREATE TABLE vault_archivos (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id      BIGINT      NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    external_id    TEXT        NOT NULL,
    -- NULL mientras no hay una version exitosa: durante 'detectado'/'extrayendo',
    -- o si la ultima corrida termino en 'error'.
    document_id    BIGINT      REFERENCES documents (id) ON DELETE SET NULL,
    estado         TEXT        NOT NULL DEFAULT 'detectado',
    last_error     TEXT,
    tamano_bytes   BIGINT      NOT NULL DEFAULT 0,
    detectado_en   TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT vault_archivos_externo_unico UNIQUE (source_id, external_id),
    CONSTRAINT vault_archivos_estado_valido CHECK (
        estado IN ('detectado', 'extrayendo', 'procesando', 'error')
    )
);

-- El panel siempre lista ordenado por ultima actividad.
CREATE INDEX vault_archivos_actualizado_idx ON vault_archivos (actualizado_en DESC);
