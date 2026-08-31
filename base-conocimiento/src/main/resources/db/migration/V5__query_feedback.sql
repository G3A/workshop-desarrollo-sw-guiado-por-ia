-- La otra mitad de la auditoria de query_log: no solo que se respondio, sino
-- si la respuesta sirvio. Tabla append-only a proposito, igual que query_log
-- (ver su propio comentario en V1): sin login de persona en el MVP no hay
-- forma real de saber si dos clicks son la misma persona insistiendo o dos
-- personas distintas viendo el mismo hilo, asi que se permiten varias filas
-- por query_log_id en vez de forzar un upsert que fingiria una identidad que
-- no existe.
CREATE TABLE query_feedback (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    query_log_id BIGINT      NOT NULL REFERENCES query_log (id) ON DELETE CASCADE,
    util         BOOLEAN     NOT NULL,
    comentario   TEXT,
    creado_en    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX query_feedback_query_log_id_idx ON query_feedback (query_log_id);
