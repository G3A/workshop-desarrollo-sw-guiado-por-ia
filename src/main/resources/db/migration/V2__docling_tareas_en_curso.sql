-- ADR-0010: la ingesta de PDF/DOCX/PPTX via docling-serve es asincrona (submit +
-- polling), y una conversion real puede tardar minutos u horas (jls25.pdf, ~900
-- paginas, midio ~26 min). Si kb-api se reinicia mientras una de esas tareas
-- sigue en curso, el candado en memoria de RelevadorDeFuentes (que ya evita que
-- el relevo dispare una ingesta duplicada MIENTRAS el proceso sigue vivo) se
-- pierde con la JVM. Sin este registro, el proximo intento no tendria forma de
-- saber que ya existe una tarea en vuelo en docling-serve, y mandaria una
-- duplicada.
--
-- Vive por (source_id, external_id), no por documento: el documento todavia no
-- existe en `documents` mientras la conversion esta en curso (esa fila solo se
-- crea al terminar con exito).
CREATE TABLE docling_tareas_en_curso (
    source_id   BIGINT      NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    external_id TEXT        NOT NULL,
    task_id     TEXT        NOT NULL,
    iniciado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_id, external_id)
);
