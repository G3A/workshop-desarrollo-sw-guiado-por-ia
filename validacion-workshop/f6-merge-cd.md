# F6 — Merge + CD

Decisión ya acordada con el usuario para esta validación: el merge del PR de F3-F5 se hace de
verdad contra `dev` (nunca `main`), para tener evidencia real en vez de un paso descrito sin
ejecutar. Confirmado explícitamente en esta etapa: "Sigamos con F6, mergeá el PR #3".

## Merge real

`gh pr merge 3 --squash --delete-branch` — verificado antes `mergeStateStatus: CLEAN`,
`mergeable: MERGEABLE`, CI en verde. Resultado: commit `e42751e` en `dev`, rama
`feature/2-archunit-seguridad-allowemptyshould` borrada en el remoto.

CI se disparó de nuevo automáticamente sobre `dev` (el `ci.yml` corre en cada `push`, cualquier
rama): run `32875428240`, `conclusion: success`. Los "X" que aparecen en el log son avisos de
Checkstyle preexistentes (`ConstantName` sobre el logger `log`, 35 violaciones de brownfield ya
documentadas en F2) — reportan, no bloquean (`failOnViolation=false`).

## CD

No hay job de despliegue en `.github/workflows/ci.yml` — solo el job `check` (build, test,
secretos). No hay CD a staging que mostrar todavía; esta etapa se documenta como "no aplica hoy",
no como paso pendiente de ejecutar.

## Hallazgo real, no anticipado: el merge de #3 volvió redundante al PR #1

`feature/2-archunit-seguridad-allowemptyshould` (la rama de F3) nació de la punta de
`validacion/f3-planificar` (== `f2`), no de `dev` — desviación deliberada documentada en
`validacion-workshop/f3-planificar.md`, tomada porque `dev` todavía no tenía mergeada la
instrumentación de F2 (PR `#1`, seguía abierto) y una rama nacida de `dev` en ese momento se habría
quedado sin `make ci` para verificar nada.

Consecuencia real, confirmada después del squash-merge de `#3`: como esa rama arrastraba todo el
contenido de F2 encima, mergear `#3` contra `dev` trajo también, de un solo golpe, todo lo que el
PR `#1` todavía no había mergeado — CI, Makefile instrumentado, hooks del agente, ArchUnit, todo.
Confirmado con `git diff --stat dev origin/validacion/f2-preparar-proyecto` después del merge: cero
contenido nuevo del lado de la rama de F2.

**Decisión tomada con el usuario:** cerrar `#1` sin mergear (`gh pr close 1 --comment ...`),
dejando explicado en el propio PR por qué se cierra así en vez de mergearlo (un merge vacío o, peor,
uno que intentara revertir contenido más nuevo de `dev` a un estado más viejo).

Esto es exactamente el tipo de fricción real que esta validación busca sacar a la luz: encadenar
ramas de trabajo sobre un PR todavía no mergeado (un patrón real y común) tiene una consecuencia de
segundo orden sobre el PR original que nadie planeó — y que solo aparece al ejecutar de verdad, no
al simular el flujo.

## Estado final de esta etapa

- `dev`: `e42751e`, con todo F2 + el fix de F3, CI en verde.
- PR `#3`: `MERGED`.
- PR `#1`: `CLOSED` (superseded by `#3`), sin mergear.
- `main`: sin tocar, como en cada etapa de esta validación.
