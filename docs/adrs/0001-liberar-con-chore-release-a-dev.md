# ADR-0001: Liberar con una PR `chore/release-<versión>` a `dev` seguida de la PR `dev` → `main`

## Estado

Aceptado — 2026-09-09.

## Contexto

El 2026-09-09 el monorepo cambió de esquema de ramas: `main` dejó de ser un snapshot didáctico y
pasó a ser la rama estable, lo liberado; `dev` es la rama de integración. Dos Rulesets lo hacen
cumplir: `release-main` (PR obligatoria, check `check` en verde, solo merge commit) e
`integration-dev` (PR obligatoria, check en verde, solo squash).

Con eso apareció una pregunta concreta: **quién decide cuándo sube la versión del plugin `sdlc-ia`
y cómo llega ese número a `main`**. Dos restricciones mecánicas la enmarcan:

- La PR de liberación tiene a `dev` como origen. Todo lo que deba estar en `main` tiene que estar
  antes en `dev`, incluida la versión en `sdlc-ia/.claude-plugin/plugin.json` y la fecha en el
  CHANGELOG.
- Nada entra a `main` ni a `dev` sin PR. No hay commit directo que «suba la versión al mergear».

Se evaluaron dos opciones:

1. **Una PR `chore/release-<versión>` a `dev`, seguida de inmediato por la PR `dev` → `main`.** La
   PR `chore/release` lleva un solo cambio, la versión y la fecha, y abrirla es la decisión de
   liberar. Dos PR por liberación.
2. **Rama de liberación al estilo git-flow.** Se corta `release/<versión>` desde `dev`, ahí va el
   único commit con la versión y la fecha, y esa rama se mergea por PR a `main` y por PR a `dev`.
   La versión llega a las dos ramas desde el mismo commit y nunca toca `dev` antes que a `main`.
   Tres PR por liberación cuando se cuenta la de vuelta a `dev`.

La restricción que pesó: hoy el repositorio lo trabaja una sola persona, que integra y libera. La
preocupación real detrás de la pregunta no era mecánica sino de responsabilidad: que subir la
versión no se convierta en algo que «pasa en `dev`» como efecto del trabajo diario, sino que sea
una decisión explícita de quien libera.

## Decisión

Opción 1. **Liberar es una decisión, y la PR `chore/release-<versión>` a `dev` es esa decisión.**
La abre quien libera, cuando `dev` está en verde y se decidió publicar, con un único cambio: la
versión en `plugin.json` y la fecha en el CHANGELOG. De inmediato le sigue la PR `dev` → `main`,
mergeada con merge commit. La PR `chore/release` nunca forma parte de una feature y no se abre «por
si acaso».

## Consecuencias

- **A favor**: dos PR por liberación en vez de tres; la versión y la fecha viven en `dev` y en
  `main` sin rama intermedia que mantener; el flujo es el mismo que el visor
  `proceso-operacional-con-ia` enseña en el cierre del ciclo (nodo G7); y la decisión queda
  registrada en un lugar visible, la PR `chore/release`, con quién la abrió y cuándo.
- **En contra**: entre el merge de la `chore/release` a `dev` y el merge a `main` hay una ventana
  en la que `dev` declara una versión que todavía no está liberada. Se maneja abriendo las dos PR
  seguidas, en la misma sesión de quien libera. Si en esa ventana entra una feature a `dev`, se
  libera igual con ella o se abre otra `chore/release` con la versión siguiente; nunca se
  «desfecha» una versión.
- **Qué haría reconsiderar**: que varias personas trabajen en `dev` mientras otra libera. Ahí la
  ventana deja de ser teórica y la opción 2, la rama `release/`, es la que evita que una versión se
  cuele en `dev` antes de tiempo. Ese cambio es un ADR nuevo que reemplace a este, no una excepción
  silenciosa.

Referencias: `AGENTS.md` de la raíz («Ramas y pull requests»), `instrumentacion-java-ia/AGENTS.md`
(«Regla dura: versión y actualización»), `instrumentacion-java-ia/CHANGELOG.md`.
