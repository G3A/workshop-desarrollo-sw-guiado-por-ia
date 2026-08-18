-- Orquestador.ejecutarEnStreaming prepara el contexto y sintetiza de forma
-- bloqueante -- ver el javadoc de MENSAJE_SERVIDOR_OCUPADO. Si el navegador
-- se desconecta a mitad de camino (un F5, cerrar la pestaña), el servidor
-- sigue trabajando igual (no hay forma de cancelarlo desde aca), pero SIN
-- este registro esa respuesta se pierde por completo: nadie la guarda en
-- IndexedDB (eso lo hace el cliente, al recibir el evento "fin" por SSE) ni
-- hay forma de que la pagina, al volver a abrirse, sepa que existio.
--
-- Una sola fila por conversacion (upsert), no una bitacora -- a diferencia de
-- query_log, que es auditoria permanente, esto es efimero: representa el
-- estado de la pregunta MAS RECIENTE de esa conversacion, se pisa con la
-- siguiente. conversacion_id es el id que ya genera IndexedDB del lado del
-- navegador (ver historial-db.js) -- no hay login de persona (MVP), asi que
-- no hace falta mas que eso para separar una conversacion de otra.
CREATE TABLE streams_en_curso (
    conversacion_id BIGINT      PRIMARY KEY,
    pregunta        TEXT        NOT NULL,
    project_id      TEXT        NOT NULL,
    estado          TEXT        NOT NULL DEFAULT 'en_curso',
    texto           TEXT        NOT NULL DEFAULT '',
    citas           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    reformulacion   TEXT,
    creado_en       TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT streams_en_curso_estado_valido CHECK (estado IN ('en_curso', 'completo', 'error'))
);
