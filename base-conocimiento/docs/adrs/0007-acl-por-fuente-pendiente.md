# ADR-0007: ACL por fuente — decisión pendiente

## Estado

Propuesto / pendiente. No implementado.

## Contexto

El plan original (Supuestos) menciona tres piezas de autenticación para el MVP: un token
compartido opcional, segmentación por `project_id`, y "ACL a nivel de fuente". De las tres, hoy
están implementadas la primera (`KB_API_TOKEN` vía `ApiTokenFilter`, módulo `seguridad`) y la
segunda (`ProyectoId` acota el corpus antes del planner, en cada query de `RecuperacionRepositorio`
y en el esquema vía `project_id`).

La tercera no está implementada, por una razón concreta: `documents.acl jsonb` existe como columna
desde F0, pero **`chunks` no tiene una columna `acl` propia** (solo `project_id`), y ningún query de
retrieval la consulta. Hacerla cumplir de verdad exige resolver primero una pregunta que el MVP no
tiene resuelta: **¿de dónde sale la identidad del que pregunta?** Ni la UI web ni el bot de Teams
tienen hoy un concepto de usuario o grupo autenticado más allá del token compartido de la API —
Entra ID queda explícitamente fuera del MVP.

Durante el diseño se evaluó qué mecanismo de Postgres sería el correcto para hacer cumplir la ACL
si existiera esa identidad: **Row-Level Security (RLS)**, nativo desde Postgres 9.5 (no una
extensión — ya disponible en `pgvector/pgvector:pg18-trixie`, no hay que instalar nada). El patrón
sería que la app resuelva la identidad, haga `SET LOCAL app.project_id = '...'` (y
`app.grupos = '...'`) por transacción, y una política RLS sobre `chunks`/`documents` exija
`project_id = current_setting('app.project_id')` y, para ACL real,
`acl ?| string_to_array(current_setting('app.grupos'), ',')`. Se descartaron dos alternativas:
`pgjwt` (asume identidad por JWT de persona, no aplica a un token compartido) y `pgcrypto` (resuelve
guardar secretos hasheados, no la pregunta de identidad).

## Decisión

Dejar la ACL de fuente **sin implementar por ahora**, documentada como decisión pendiente en vez de
como omisión silenciosa. `documents.acl` sigue existiendo en el esquema (no se retira: los
conectores ya la pueblan si una fuente la trae) pero no se propaga a `chunks` ni se hace cumplir en
ningún query.

## Consecuencias

- **Hoy**: el aislamiento real entre "quién puede ver qué" es `project_id`, no `acl`. Cualquiera con
  el `KB_API_TOKEN` (o acceso a la UI web, que no lo exige — ver
  [ADR sobre autenticación en `docs/architecture.md`](../architecture.md#autenticación)) puede
  consultar cualquier `project_id` que conozca. Esto es aceptable para el alcance actual (un
  workshop/demo, sin Entra ID) pero **no** es una ACL real por fuente.
- **Si se retoma**: el camino más consistente con el resto del diseño (SQL a mano sobre
  `JdbcClient`, sin abstracciones genéricas — ver
  [`docs/architecture.md`](../architecture.md#dónde-termina-spring-ai)) es RLS, no un filtro en
  código Java repetido en cada query nuevo — el mismo argumento por el que `project_id` ya se
  filtra en SQL y no en una capa aparte.
- Requiere primero resolver la identidad: agregar `chunks.acl` vía migración, decidir cómo un
  request llega con grupos (header propio, claim de un JWT si se adopta Entra ID más adelante), y
  recién ahí escribir las políticas RLS y su prueba de aislamiento entre proyectos/grupos.
