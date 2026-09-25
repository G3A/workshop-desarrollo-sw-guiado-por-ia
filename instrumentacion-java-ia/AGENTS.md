# AGENTS.md — instrumentacion-java-ia (plugin `sdlc-ia`)

Plugin de Claude Code con nueve skills. Las convenciones del monorepo (ramas, commits, idioma)
están en el `AGENTS.md` de la raíz; este archivo cubre solo lo propio del plugin.

## Regla dura: versión y actualización

- **Una versión por liberación.** `main` es lo publicado; `dev` es integración. Cada PR a `dev`
  que cambie una skill registra su entrada en el `CHANGELOG.md` bajo la sección «unreleased». La
  liberación sube `version` en `sdlc-ia/.claude-plugin/plugin.json`, convierte esa sección en la
  versión nueva y le pone la fecha. Quién y cuándo: quien libera, en el momento de liberar, nunca
  como parte de una feature; mecánicamente es una PR `chore/release-<versión>` a `dev` con ese
  único cambio, seguida de inmediato por la PR `dev` → `main` (ADR 0001 en la raíz del
  monorepo). La versión es lo
  que permite saber qué copia corre cada equipo (`claude plugin list`); no es lo que dispara la
  actualización.
- **El plugin carga en su lugar, no desde una caché** (comprobado con Claude Code 2.1.282, #209):
  la carpeta del clon registrada como marketplace, con su rama y sus cambios sin commitear, es el
  plugin que corre desde la próxima sesión o `/reload-plugins`; un worktree enlazado no. Un equipo
  que quiere lo estable mantiene ese clon en `main`; el que quiere lo último, en `dev`. El
  `installPath` que registra la CLI apunta a la caché y no dice qué corre.
- **Actualizar en cualquier equipo es un solo comando:** `.\update.ps1` en PowerShell 5.1 o 7,
  `./update.sh` en bash. Hace `git pull --ff-only`, registra el marketplace sobre esta carpeta si
  falta o apunta a otra ruta, instala si falta, re-registra la versión y falla si la CLI no dice
  que carga en su lugar desde esta carpeta. Nunca documentes otra secuencia: el script es la
  fuente de verdad.

## Otras reglas

- Los `SKILL.md` y sus `references/` van en inglés; `docs/skills/<skill>-es.md` es su doc en
  español. Se actualizan juntos en el mismo PR.
- Todo comando que una skill le pide ejecutar al agente funciona igual en Windows PowerShell 5.1,
  PowerShell 7 y bash (sección «Shell» del `README.md`). Las excepciones deliberadas son los hooks
  de `instrument-agent-java` (bash) y el workflow de CI (Ubuntu).
- Si una skill cambia de comportamiento, el nodo del visor `proceso-operacional-con-ia` que la
  cita cambia en el mismo PR (`grep` del nombre de la skill en `comandos.json`).
- Este plugin escribe solo GitHub Actions como CI y GitHub Issues como tracker; otra plataforma se
  reporta fuera de alcance, no se adapta a mano.
- Pruebas de los hooks: `bash tests/run.sh`.
