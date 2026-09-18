# AGENTS.md — workshop-desarrollo-sw-guiado-por-ia

Monorepo del taller de desarrollo de software guiado por IA. Cuatro piezas, cada una con su propio
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
- `scripts/` — lo que es del repositorio entero y no de una pieza: los hooks del agente
  (`agent-hooks/`, solo para Claude Code), `verificar-enlaces.mjs`, el sensor que revisa los
  enlaces de todos los `.md`, y desde #155 `verificar-espejo.mjs`, el que compara
  `base-conocimiento/` con `base-conocimiento-sandbox` por hashes de blob contra las listas del
  ADR-0003 y, desde #162, también los criterios de los dos `REVIEW.md` por los títulos de sus
  secciones numeradas. Desde #165 está además `verificar-ancho.mjs`, que falla cuando una línea de
  prosa de un `.md` o un `.mjs` de los que este método mantiene pasa de 100 caracteres; su alcance
  y sus 25 archivos heredados están escritos en el propio script. Los cuatro sensores del monorepo
  piden **node >= 18** en el PATH; el del playbook vive junto a lo que verifica
  (`playbook-sdlc-ia/verificar-cobertura.mjs`). Dónde bloquea cada uno difiere: los de enlaces y de
  ancho corren en CI y también en el pre-push, porque son locales y de un segundo; el del playbook,
  solo en CI; y el del espejo, **solo en CI porque sale a la red** —un hook que sale a la red
  bloquea `git push` cuando falla el wifi—. A mano es `node scripts/verificar-espejo.mjs` desde la
  raíz; sin `GITHUB_TOKEN` en el entorno usa la API anónima, que permite 60 peticiones por hora.
- `REVIEW.md` — qué mirar en un diff ya escrito (lo lee el servicio de Code Review; no repite las
  reglas de generación de los `AGENTS.md`). `EXPERIMENTS.md` — el acuerdo sobre qué puede fallar
  con el agente; sus pendientes los completa el equipo.

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
  estilo git-flow: `docs/adrs/0001-liberar-con-chore-release-a-dev.md`. Si la PR `dev` → `main` llega
  sin ese primer paso, el job `changelog-liberado` del CI falla: el CHANGELOG todavía empieza con
  `[unreleased]`.
- `Closes #N` cierra el issue cuando el commit llega a `main`, es decir, con la PR de liberación;
  el merge de la rama de trabajo a `dev` no lo cierra. Si el issue debe cerrarse antes, se cierra a
  mano y se dice en el comentario final.
- El merge a `dev` es por squash con el cuerpo de la PR como mensaje, para que el `Closes` y el
  trailer lleguen al commit final. La PR de liberación `dev` → `main` se mergea con merge commit,
  para conservar esos commits tal cual.
- Dos Rulesets lo hacen cumplir: `integration-dev` (PR con una aprobación, check `check` en verde
  y la rama al día con `dev`, solo squash) y `release-main` (PR con una aprobación, checks `check` y
  `changelog-liberado` en verde, solo merge commit). En `release-main` los checks **no** exigen
  que `dev` esté al día con `main`: como `dev` se integra por squash, nunca contiene los merge
  commits de las liberaciones anteriores, y con esa exigencia toda liberación quedaría bloqueada
  como «behind». Mientras el repo tenga una sola persona, ambos Rulesets llevan bypass del rol
  Administrador, porque nadie puede aprobar su propia PR; al sumarse alguien, se retira.

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
