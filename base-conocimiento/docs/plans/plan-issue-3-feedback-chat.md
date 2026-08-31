# Feedback "esta respuesta no sirvió" — base-conocimiento-sandbox

## Contexto

Issue real: [G3A/base-conocimiento-sandbox#3](https://github.com/G3A/base-conocimiento-sandbox/issues/3).
Cada consulta al chat ya se audita completa en `query_log` (pregunta, plan, respuesta, citas,
latencia — `QueryLogRepositorio`), pero nadie puede decir "esta respuesta no sirvió". Sin esa
señal, `query_log` registra lo que el sistema hizo, no si funcionó, y el corpus nunca se corrige
con el uso real.

Este plan ya pasó por una revisión adversarial en 3 lentes (convenciones, corrección, alcance)
contra el código real del repo. Las secciones **Resuelto** documentan qué encontró cada lente y
cómo quedó zanjado — incluido lo que se rechazó y por qué.

## Decisiones ya cerradas con el usuario

- Modelo de datos: tabla nueva `query_feedback`, append-only (no se toca `query_log`).
- Duplicados: se permiten varias filas por `query_log_id` (no hay login de persona en el MVP); el
  front deshabilita los botones tras el primer click.
- `POST /api/feedback` público, sin `ApiTokenFilter` — igual categoría que `/api/chat`.
- `GET /api/admin/feedback` protegido por `ApiTokenFilter`, JSON plano — sin panel nuevo en
  `admin.html` en este corte.

## Hallazgos de la revisión adversarial y su resolución

| # | Lente(s) | Hallazgo | Resolución |
|---|---|---|---|
| 1 | Corrección + Convenciones | El camino "servidor ocupado" **sí** llama a `queryLog.registrar(...)` hoy (mi premisa original era falsa) | No se distingue el caso: se emite `queryLogId` para **toda** respuesta completada, incluida "ocupado"/"sin información" — es una fila real y feedback sobre una respuesta de "ocupado" es una señal legítima |
| 2 | Las 3 lentes | Falta el paso que agrega `/api/feedback` a `ApiTokenFilter.RUTAS_SIN_TOKEN` | Paso explícito en el plan (ver Backend, paso 5) |
| 3 | Corrección + Alcance | Reconexión tras F5 (`GET /api/chat/estado`) no expone `queryLogId` — deja mudos los botones en el escenario que `streams_en_curso` fue construido a propósito para soportar | Se extiende `streams_en_curso`/`EstadoStream`/`StreamsEnCursoRepositorio.finalizar` con `query_log_id` nullable (ver Backend, paso 4) |
| 4 | Convenciones | Migración con `BIGSERIAL` rompe el estilo real (todas usan `GENERATED ALWAYS AS IDENTITY`); falta prosa explicativa y `ON DELETE` explícito en la FK | Se corrige el DDL al estilo real (ver Migraciones) |
| 5 | Convenciones | No hay un test por repositorio en `orquestacion` — existe `OrquestacionRepositoriosTest` agrupando varios contra Postgres real | Los métodos de `QueryFeedbackRepositorio` se prueban ahí, no en una clase nueva |
| 6 | Convenciones + Corrección | "Traducir violación de FK a 400" no tiene mecanismo en el repo (no hay `@ExceptionHandler`) y catalogar por excepción de integridad puede enmascarar errores no relacionados | Se reemplaza por el idiom ya usado en `estadoDeStream`/`ChatController.estado()`: `Consultar.registrarFeedback(...)` devuelve `boolean` (false = `queryLogId` no existe, chequeado con un `SELECT EXISTS` antes de insertar); el controller mapea `false` a `badRequest()`, igual que ya hace con `Optional.empty()` → `notFound()`. Cero manejo de excepciones nuevo. |
| 7 | Convenciones | `Sinks.One` sería la primera aparición de Reactor Sinks en el repo; el idiom ya usado es una variable mutable capturada en closure (`acumulado`, y el propio `StreamsEnCursoRepositorio.finalizar`) | Se usa un `AtomicReference<Long>` mutado dentro del mismo `doOnComplete` que ya llama a `queryLog.registrar(...)`, expuesto al llamador como `Mono<Long> queryLogId()` construido con `Mono.fromSupplier(holder::get)` — el tipo reactivo en el contrato no es nuevo (`RespuestaEnStreaming` ya expone `Flux<String>`), solo se evita la API de `Sinks` |
| 8 | Alcance + Convenciones | El precedente citado para `FeedbackAdminController` (`OrquestacionController`) es válido arquitectónicamente pero no es el precedente real de `/api/admin/*` (ese es `AdminController`, en `ingesta`) | Se documenta la razón real en el javadoc del controller nuevo (mismo patrón que `OrquestacionController`: endpoint operativo dentro de su propio módulo — la regla de ArchUnit que aísla adaptadores no aplica a código intra-módulo) y se actualiza la frase de `docs/architecture.md` que hoy dice que `/api/admin/*` vive solo en `ingesta` |
| 9 | Corrección + Alcance | Sin control de que el `queryLogId` pertenezca al proyecto de quien manda el feedback — IDs correlativos + endpoint público habilitan spam/vandalismo trivial | **Riesgo aceptado, documentado explícitamente** en el PR y en un comentario en el código: el MVP no tiene login de persona ni sesión, así que no hay identidad real contra la cual validar pertenencia; construir eso es una iniciativa de autenticación aparte, fuera de alcance de este issue. No se finge una solución (p. ej. comparar contra un `projectId` autodeclarado por el propio cliente no aporta seguridad real) |
| 10 | Alcance | Límite de 2000 caracteres en `comentario` era una asunción mía no marcada como tal | Se mantiene 2000 como default defensivo, documentado explícitamente como Asunción (no pedida por el issue) en el javadoc del record de request |
| 11 | Alcance | Botones de feedback no sobreviven si se reabre una conversación vieja desde el historial de IndexedDB (`historial-db.js` no guarda `queryLogId` por turno) | **Fuera de alcance, explícito**: este corte cubre respuesta en vivo + reconexión tras F5 a mitad de stream (hallazgo #3), no el historial ya cerrado. Evita un bump de `VERSION_BD` y una migración de esquema del lado del cliente que el issue no pide |
| 12 | Alcance | `/api/admin/feedback` sin paginación/filtro de fecha | Se limita a las 500 filas más recientes por `creado_en DESC`, con el tope declarado en el javadoc del endpoint (sin cap silencioso) |

## Migraciones

Dos migraciones, cada una con su propio propósito (mismo grano que `V2`/`V3`/`V4`), estilo real
(`GENERATED ALWAYS AS IDENTITY`, `ON DELETE`, prosa explicando el porqué):

- **`V5__query_feedback.sql`**: `query_feedback(id GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  query_log_id BIGINT NOT NULL REFERENCES query_log(id) ON DELETE CASCADE, util BOOLEAN NOT NULL,
  comentario TEXT, creado_en TIMESTAMPTZ NOT NULL DEFAULT now())` + índice sobre `query_log_id`.
  `ON DELETE CASCADE` es defensivo (hoy `query_log` es append-only y nunca se borra).
- **`V6__streams_en_curso_query_log_id.sql`**: `ALTER TABLE streams_en_curso ADD COLUMN
  query_log_id BIGINT REFERENCES query_log(id) ON DELETE SET NULL` (nullable: el camino de error
  nunca escribe en `query_log`).

## Backend (módulo `orquestacion`)

1. **`QueryFeedbackRepositorio`** (nuevo, `JdbcClient`, mismo patrón que `QueryLogRepositorio`):
   `long registrar(long queryLogId, boolean util, String comentario)`, `boolean existe(long
   queryLogId)`, `List<FeedbackRegistrado> listarRecientes(int limite)` (record con
   `id, queryLogId, util, comentario, creadoEn`).
2. **`Consultar`**: agrega `boolean registrarFeedback(long queryLogId, boolean util, String
   comentario)` — `false` si `queryLogId` no existe (ver hallazgo #6). Javadoc explícito sobre el
   límite de 2000 caracteres como asunción de producto (hallazgo #10).
3. **`Consultador`**: inyecta `QueryFeedbackRepositorio`; `registrarFeedback` llama primero a
   `existe(...)`, y solo si es `true` llama a `registrar(...)`.
4. **`Orquestador.ejecutarEnStreaming`**: declara `AtomicReference<Long> queryLogIdHolder = new
   AtomicReference<>()` antes de construir `texto`; dentro del `doOnComplete` existente (donde ya
   se llama a `queryLog.registrar(...)`), guarda el id devuelto en el holder — sin condicionar por
   tipo de respuesta (hallazgo #1). El `doFinally` existente pasa `queryLogIdHolder.get()` como
   nuevo 4to argumento de `streamsEnCurso.finalizar(...)` (hallazgo #3). El método devuelve
   `RespuestaEnStreaming` con un 4to componente `Mono<Long> queryLogId()` implementado como
   `Mono.fromSupplier(queryLogIdHolder::get)` (hallazgo #7) — se resuelve solo cuando alguien lo
   suscribe, después de que `doOnComplete` ya corrió (mismo orden ya usado hoy entre `doOnComplete`
   y `doFinally`).
5. **`StreamsEnCursoRepositorio`**: `finalizar(long conversacionId, String estado, String texto,
   Long queryLogId)` — nuevo 4to parámetro, columna nueva de la V6. `Estado` record y `buscar(...)`
   devuelven también `queryLogId` (nullable).
6. **`Consultador.estadoDeStream`**: el `EstadoStream` record (en `Consultar`) gana un componente
   `Long queryLogId`, mapeado desde `StreamsEnCursoRepositorio.Estado`.
7. **`FeedbackAdminController`** (nuevo, dentro de `orquestacion`, mismo precedente que
   `OrquestacionController` — hallazgo #8, con la justificación correcta en el javadoc): `GET
   /api/admin/feedback` → `queryFeedbackRepositorio.listarRecientes(500)` (hallazgo #12).

## Backend (módulo `web`)

8. **`ChatController`**:
   - `Flux.concat(eventoCitas, eventoReformulacion, eventosTexto, eventoQueryLogId, eventoFin)` —
     `eventoQueryLogId` construido desde `resultado.queryLogId().map(id ->
     ServerSentEvent.builder().event("queryLogId").data(String.valueOf(id)).build()).flux()`.
   - `estado(...)` (`GET /api/chat/estado`): el `EstadoStream` ya trae `queryLogId`, no requiere
     cambio de firma, solo se serializa (Jackson lo hace solo al ser un record).
   - Nuevo `record FeedbackWeb(@NotNull Long queryLogId, @NotNull Boolean util, @Size(max = 2000)
     String comentario)` y `POST /api/feedback` → si `consultar.registrarFeedback(...)` devuelve
     `false`, `ResponseEntity.badRequest()`; si `true`, `ResponseEntity.ok().build()`.

## Seguridad

9. **`ApiTokenFilter.RUTAS_SIN_TOKEN`**: agrega `"/api/feedback"` (hallazgo #2). `GET
   /api/admin/feedback` queda cubierto por defecto, igual que el resto de `/api/admin/*`.

## Frontend (`app.js`, `index.html`)

10. En el handler `fuente.addEventListener("queryLogId", ...)` (nuevo, junto a los de
    `citas`/`token`/`fin`), guardar el id en `turno.queryLogId`.
11. En el bloque de acciones del `turno` (donde `nuevoTurno` arma el DOM de cada respuesta),
    agregar dos botones 👍/👎. Al click: `fetch("/api/feedback", {method: "POST", ...})` con el
    `queryLogId` guardado, deshabilitar ambos botones tras el primer click (cumple "una vez por
    respuesta" del lado del cliente, hallazgo del criterio de aceptación original), mostrar
    confirmación visual corta.
12. En `renderizarStreamResuelto` (camino de reconexión tras F5, hallazgo #3): si `estado.queryLogId`
    no es null, mostrar los mismos botones; si es null (camino de error, sin fila en `query_log`),
    no mostrarlos.
13. **Fuera de alcance explícito** (hallazgo #11): no se tocan `historial-db.js` ni `VERSION_BD` —
    una conversación reabierta desde el historial de IndexedDB no muestra botones de feedback en
    este corte.

## Documentación

14. `docs/data-model.md`: agrega `query_feedback` a la tabla de "Soporte" y la columna nueva de
    `streams_en_curso`.
15. `docs/architecture.md`: corrige la frase que dice que `/api/admin/*` vive solo en `AdminController`
    (`ingesta`) — ahora hay dos controllers admin, cada uno en su módulo, con su propia razón
    (hallazgo #8).

## Verificación

- `QueryFeedbackRepositorioTest` (dentro de `OrquestacionRepositoriosTest`, Testcontainers/Postgres
  real): `registrar` inserta, `existe` distingue presente/ausente, `listarRecientes` respeta el
  límite y el orden.
- `ChatControllerTest`: actualizar los 3 sitios reales que construyen `RespuestaEnStreaming` (no 4,
  hallazgo de conteo de Convenciones) con el nuevo componente `Mono<Long>`; nuevo test para el
  evento SSE `queryLogId` (aparece después de los tokens, antes de `fin`); nuevos tests para `POST
  /api/feedback` (200 delegando a `Consultar`, 400 cuando `Consultar` devuelve `false`, 400 en
  payload inválido); test para `GET /api/chat/estado` incluyendo `queryLogId` en el JSON.
- `ArquitecturaTest` (ArchUnit): correr entero — `web` sigue sin tocar `orquestacion` salvo por
  `Consultar`.
- `make check` (lint + build + test) y `make ci` en verde antes de abrir PR.
- Verificación manual en `http://localhost:8080`: preguntar algo, click en 👍/👎, confirmar fila en
  `query_feedback` (`make psql`), recargar la página a mitad de una respuesta (F5) y confirmar que
  los botones aparecen igual tras reconectar.

## Post-implementación

Lo que el plan no anticipó, encontrado durante la ejecución real:

- **Seguridad, no planeado**: `jqwik` 1.10.x imprime una inyección de prompt contra agentes de IA
  en cada corrida de tests (ver `pom.xml`, comentario junto a `jqwik.version`, y
  https://lwn.net/Articles/1075317/). Se bajó a 1.9.3 antes de tocar el resto del plan.
- **Bug real, encontrado en la revisión de código de la PR** (no por esta revisión adversarial):
  `StreamsEnCursoRepositorio.buscar()` llamaba a `rs.wasNull()` después de leer `reformulacion`, así
  que reflejaba la nulidad de esa columna, no la de `query_log_id` (leída antes) — corregido, con
  `StreamsEnCursoRepositorioTest` como regresión.
- **Bug menor, misma revisión**: `app.js` mostraba la confirmación de feedback sin chequear si
  `POST /api/feedback` respondió 200.
- **Gobernanza**: el Ruleset de `dev` no tenía ningún bypass actor — en un repo de una sola persona,
  eso bloqueaba cualquier merge (nadie puede aprobar su propia PR). Se agregó un bypass de
  `RepositoryRole` admin; ver `AGENTS.md` § Gobernanza.

Issue cerrado, PR [#4](https://github.com/G3A/base-conocimiento-sandbox/pull/4) y PR
[#5](https://github.com/G3A/base-conocimiento-sandbox/pull/5) (retrospectiva) mergeadas a `dev`.
