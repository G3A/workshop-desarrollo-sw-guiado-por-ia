# AGENTS.md — workshop-desarrollo-sw-guiado-por-ia

Monorepo del taller de desarrollo de software guiado por IA. Tres piezas, cada una con su propio
contexto:

- `base-conocimiento/` — la aplicación Java/Spring del taller. Tiene su `AGENTS.md`.
- `instrumentacion-java-ia/` — el plugin `sdlc-ia` de Claude Code (ocho skills). Tiene su `AGENTS.md`.
- `proceso-operacional-con-ia/` — el visor BPMN del proceso, que enseña con comandos lo que las
  skills ejecutan.
- `playbook-sdlc-ia/` — el diagrama de las 7 fases del método con un badge de cobertura por caja
  (skill / parcial / a mano / hueco / fuera de alcance). Es el único lugar donde está escrito qué
  del método NO cubre el plugin todavía.
- `docs/adrs/` — decisiones que atraviesan el monorepo (ramas, liberaciones). Las de cada pieza
  viven en su carpeta.

## Ramas y pull requests

- **`dev` es la rama de integración.** Toda rama de trabajo abre su PR contra `dev`.
- **`main` es la rama estable, lo liberado.** Solo recibe PRs de liberación desde `dev`, cuando
  `dev` está en verde y se decide publicar. Ninguna rama de trabajo abre PR contra `main`. Lo que
  `main` tenía antes de este esquema vive en `snapshot/main-antes-del-primer-release`.
- Ramas de trabajo: `feat/`, `fix/` o `docs/`, más el número del issue y un slug:
  `feat/62-visor-y-skills-alineados`. Las tareas de mantenimiento sin issue, como preparar una
  liberación, usan `chore/` con un slug: `chore/release-0.2.0`.
- **Liberar es una decisión, no un efecto del trabajo en `dev`.** La toma quien libera, cuando
  `dev` está en verde y se decide publicar, y se ejecuta en dos PR seguidas: primero
  `chore/release-<versión>` a `dev`, que sube la versión del plugin y fecha el CHANGELOG, y de
  inmediato la PR `dev` → `main`. La PR `chore/release` no forma parte de ninguna feature y no se
  abre «por si acaso»: abrirla es decidir liberar. Por qué así y no con una rama `release/` al
  estilo git-flow: `docs/adrs/0001-liberar-con-chore-release-a-dev.md`.
- `Closes #N` cierra el issue cuando el commit llega a `main`, es decir, con la PR de liberación;
  el merge de la rama de trabajo a `dev` no lo cierra. Si el issue debe cerrarse antes, se cierra a
  mano y se dice en el comentario final.
- El merge a `dev` es por squash con el cuerpo de la PR como mensaje, para que el `Closes` y el
  trailer lleguen al commit final. La PR de liberación `dev` → `main` se mergea con merge commit,
  para conservar esos commits tal cual.
- Dos Rulesets lo hacen cumplir: `integration-dev` (PR con una aprobación, check `check` en verde
  y la rama al día con `dev`, solo squash) y `release-main` (PR con una aprobación, check en verde,
  solo merge commit). En `release-main` el check **no** exige que `dev` esté al día con `main`:
  como `dev` se integra por squash, nunca contiene los merge commits de las liberaciones
  anteriores, y con esa exigencia toda liberación quedaría bloqueada como «behind». Mientras el
  repo tenga una sola persona, ambos Rulesets llevan bypass del rol Administrador, porque nadie
  puede aprobar su propia PR; al sumarse alguien, se retira.

## Commits

- Un commit por paso en verde, con `Refs #N`; el que completa el último paso lleva `Closes #N`.
- Todo commit asistido por IA termina con el trailer `Asistido-por-IA: <modelo>` en su propio
  párrafo, separado del `Closes` por una línea en blanco.

## Reglas duras

- **Visor, playbook y skills coinciden.** Al tocar una skill, revisa el nodo del visor que la cita
  y la caja del playbook que la nombra; al tocar el visor o el playbook, verifica la afirmación
  contra el `SKILL.md`. Lo que el alumno hace a mano y lo que corre la skill tiene que ser lo
  mismo. Si una skill cierra un hueco, la caja del playbook cambia de `:::hueco` a `:::skill` y
  los contadores de cobertura del índice y de la fase se actualizan en el mismo PR.
- **Comandos.** Los bloques del visor están en PowerShell 5.1 y 7; los comandos de las skills son
  neutrales entre PowerShell y bash.
- **Versión del plugin.** La PR de liberación `dev` → `main` sube la versión de `plugin.json` y
  fecha su entrada del CHANGELOG; las PR a `dev` registran lo suyo bajo la entrada «unreleased».
  La actualización en cualquier equipo es `instrumentacion-java-ia/update.ps1` o `update.sh`.
  Detalle en `instrumentacion-java-ia/AGENTS.md`.
- **Idioma.** Todo texto en español va en español latinoamericano neutro con tuteo: sin voseo y
  sin formas peninsulares.
- Los `SKILL.md` del plugin van en inglés.
