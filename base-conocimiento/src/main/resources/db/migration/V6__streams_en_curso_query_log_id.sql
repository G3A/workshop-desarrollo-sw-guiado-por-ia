-- Sin esta columna, alguien que recarga la pagina a mitad de una respuesta
-- (el escenario que streams_en_curso existe para soportar, ver su comentario
-- en V4) reconecta y nunca recibe el id de query_log de esa respuesta -- los
-- botones de feedback quedarian mudos justo en el camino ya soportado.
-- Nullable: el camino de error nunca llega a escribir en query_log, asi que
-- no siempre hay un id que guardar.
ALTER TABLE streams_en_curso
    ADD COLUMN query_log_id BIGINT REFERENCES query_log (id) ON DELETE SET NULL;
