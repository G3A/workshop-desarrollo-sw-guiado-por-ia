-- Diagnostico de por que una pregunta responde "No encontre informacion
-- suficientemente relevante en la base de conocimiento".
--
-- Ese mensaje (Orquestador.MENSAJE_SIN_INFORMACION) sale de DOS puntos que no
-- significan lo mismo, y ninguno de los dos deja rastro de error en los logs:
--
--   Orquestador.java:369  la recuperacion puntuo INSUFICIENTE. El LLM nunca
--                         vio el contenido. Es un problema de BUSQUEDA.
--   Orquestador.java:388  puntuo AMBIGUO, se llamo a VerificadorGrounding y
--                         dictamino que el contexto no responde. El LLM SI vio
--                         el contenido y lo rechazo. Es un problema de JUICIO.
--
-- Antes de tocar umbrales o parametros de recuperacion hay que descartar que el
-- problema sea, mucho mas prosaicamente, que lo que se busca no esta embebido.
-- "La base no esta vacia" no alcanza como respuesta: `chunks.embedding` es
-- NULLABLE a proposito (la ingesta inserta los chunks primero y
-- TrabajadorEmbebido los embebe despues, de forma asincrona), asi que hay un
-- estado intermedio -- que con jls25.pdf y sus ~1060 chunks dura un buen rato --
-- donde `documents` y `chunks` tienen miles de filas, /admin.html muestra el
-- archivo, y la busqueda vectorial no encuentra NADA porque el indice HNSW no
-- tiene que indexar. Ese estado se ve igual que un problema de ranking desde la
-- interfaz de usuario, y no lo es.
--
-- Como correrlo (el stack tiene que estar arriba: `docker ps` debe listar kb-db).
-- La shell de este proyecto es PowerShell, asi que esa forma va primero. Las
-- tres quedaron verificadas en Windows y cada una tiene su trampa.
--
--   PowerShell (lo habitual aqui) -- OJO: `<` NO existe como operador de
--   redireccion de entrada, falla con "The '<' operator is reserved for future
--   use". Va por tuberia:
--     Get-Content scripts/diagnostico-ingesta.sql | docker exec -i kb-db psql -U kb -d baseconocimiento
--
--   Cualquier shell, sin depender de la redireccion -- copia el archivo al
--   contenedor y lo corre con -f:
--     docker cp scripts/diagnostico-ingesta.sql kb-db:/tmp/diagnostico.sql
--     docker exec kb-db psql -U kb -d baseconocimiento -f /tmp/diagnostico.sql
--
--   Linux / macOS / Git Bash -- aqui la redireccion si funciona:
--     docker exec -i kb-db psql -U kb -d baseconocimiento < scripts/diagnostico-ingesta.sql
--
--   OJO en Git Bash con la forma portable: MSYS reescribe /tmp/diagnostico.sql a
--   una ruta de Windows ANTES de que docker la vea, y psql falla con "No such
--   file or directory" apuntando a C:/Users/.../Temp/. Se desactiva por
--   invocacion:
--     MSYS_NO_PATHCONV=1 docker exec kb-db psql -U kb -d baseconocimiento -f /tmp/diagnostico.sql

\echo ''
\echo '== 1. Cobertura por documento =============================================='
\echo 'pendientes > 0  -> no es recuperacion, es el worker de embeddings.'
\echo '                   Revisa que bge-m3 este (make health) y kb-ollama vivo.'
\echo 'sin el documento que responde la pregunta -> la base no esta vacia, pero'
\echo '                   no tiene el contenido que hace falta.'
\echo ''

SELECT d.id,
       left(d.title, 40)                     AS documento,
       count(c.id)                           AS chunks,
       count(c.embedding)                    AS embebidos,
       count(c.id) - count(c.embedding)      AS pendientes,
       d.ingested_at
FROM documents d
         LEFT JOIN chunks c ON c.document_id = d.id
GROUP BY d.id, d.title, d.ingested_at
ORDER BY chunks DESC;

\echo ''
\echo '== 2. Totales =============================================================='
\echo 'Con todo embebido y el documento correcto presente, el problema SI esta en'
\echo 'la recuperacion. Mira entonces el reparto del punto 3.'
\echo ''

SELECT count(*)                                          AS chunks_totales,
       count(embedding)                                  AS embebidos,
       count(*) - count(embedding)                       AS pendientes,
       (SELECT count(*) FROM documents)                  AS documentos
FROM chunks;

\echo ''
\echo '== 3. Concentracion del corpus ============================================'
\echo 'kb.recuperacion.tope-por-documento acota cuantos chunks de UN MISMO'
\echo 'documento sobreviven la fusion RRF antes de llegar al reranker. Esta'
\echo 'pensado para dar diversidad entre VARIOS documentos: cuando un solo'
\echo 'documento domina el corpus, el tope descarta de entrada la mayoria de ese'
\echo 'documento sin que el cross-encoder llegue a verla. Con un porcentaje alto'
\echo 'aca y el default del perfil base (3), esa es la causa mas probable.'
\echo ''

SELECT left(d.title, 40)                                              AS documento,
       count(c.id)                                                    AS chunks,
       round(100.0 * count(c.id) / nullif((SELECT count(*) FROM chunks), 0), 1)
                                                                      AS pct_del_corpus
FROM documents d
         JOIN chunks c ON c.document_id = d.id
GROUP BY d.id, d.title
ORDER BY chunks DESC
LIMIT 5;

\echo ''
\echo '== 4. Trabajos de ingesta fallidos o encallados ============================'
\echo 'Un docling caido a mitad de un PDF deja chunks parciales: base no vacia,'
\echo 'cobertura incompleta. `running` con locked_at viejo = trabajo encallado.'
\echo ''

SELECT kind,
       status,
       count(*)                    AS trabajos,
       max(attempts)               AS intentos_max,
       max(locked_at)              AS ultimo_lock,
       left(max(last_error), 160)  AS ultimo_error
FROM ingest_jobs
GROUP BY kind, status
ORDER BY status, kind;
