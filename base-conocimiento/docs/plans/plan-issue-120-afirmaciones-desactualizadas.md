# Afirmaciones desactualizadas en los docs — plan del issue #120

Issue: [G3A/workshop-desarrollo-sw-guiado-por-ia#120](https://github.com/G3A/workshop-desarrollo-sw-guiado-por-ia/issues/120).
Plan aprobado el 2026-09-14 tras la revisión adversarial en tres lentes (convenciones, corrección,
alcance); la última sección dice qué marcó cada una.

## Contexto

La corrida de `/sdlc-ia:agent-context-java` en modo aumentar (verificación de #114) encontró
afirmaciones que ya no son ciertas en `docs/infrastructure.md` y `docs/java.md`. También encontró
una regla de ArchUnit (`compartidoEsHoja`) que nace verde si el paquete desaparece y un `AGENTS.md`
que duplica los docs y pasa el techo de ~80 líneas. Todo vale en **los dos repos**: el monorepo
(`base-conocimiento/`, rama `docs/120-afirmaciones-desactualizadas-base-conocimiento` desde `dev`)
y `G3A/base-conocimiento-sandbox`. Resultado: docs fieles al código, una regla que muerde, un
`AGENTS.md` índice y el ledger con lo corregido.

## Decisión (usuario)

- «AGENTS.md tiene 152 líneas en el monorepo y 135 en el sandbox, contra el techo de ~80 para un
  índice. ¿Qué hacemos en este issue?» → **«Recortar aquí»**: «En los dos repos, mover a docs/ lo
  que ya está duplicado ahí: CI y quality gates, Pruebas, Estilo, Hooks y MCP pasan a java.md o
  infrastructure.md, y AGENTS.md deja un enlace de una línea. Quedan Dónde encontrar, Comandos,
  Reglas no obvias y Seguridad.» (vista: «→ CI/gates, pruebas, estilo: docs/java.md · → hooks, MCP:
  docs/infrastructure.md»)

## Supuestos (revísalos: para esto es el checkpoint)

- **S1.** Sin entrada en `instrumentacion-java-ia/CHANGELOG.md`, porque solo se toca
  `base-conocimiento` (precedente: #111).
- **S2.** Criterio de alcance: se corrige toda afirmación vieja **en los archivos que este cambio
  toca**. Eso suma cuatro al issue:
  - `architecture.md:48`, la misma lista de `compartido`, idéntica en los dos repos;
  - «14 usos de `@Value`» en `java.md:68`: hoy son 22;
  - «5 pruebas» en los dos ledgers: hoy son 6 pruebas, 5 de ArchUnit y 6 `noClasses()`;
  - «WireMock solo dobla el JWKS de Bot Framework»: también se usa para Azure DevOps, Graph y
    Ollama.
- **S3.** «Un enlace de una línea» se lee literal: en `AGENTS.md` quedan los encabezados
  `## Pruebas`, `## Estilo de código`, `## CI`, `## Hooks del agente` y `## MCP`, cada uno con una
  línea y su enlace. Así `instrument-agent-java` (`references/report-and-docs.md:8-10`) e
  `instrument-project-java` (`SKILL.md:231`) actualizan esas secciones en vez de volver a crearlas
  duplicadas, y el ancla `#hooks-del-agente` sigue viva. Si con eso se pasa de 80, el sobrante se
  saca de «Comandos» y «Dónde encontrar», no de las reglas.
- **S4.** Lo que sale de `AGENTS.md` y es una **regla para el agente** no sale: queda como una
  viñeta corta en «Reglas no obvias». En el monorepo:
  - `ci.yml`, `lefthook.yml`, `.claude/settings.json` y `.mcp.json` viven en la raíz.

  En el sandbox:
  - `sh ./mvnw`;
  - `log` en minúscula, que no se prohíbe;
  - el squash no toma el cuerpo de la PR y el `Closes` se cierra a mano;
  - `LEFTHOOK=0`.

  A `docs/` solo va lo descriptivo:
  - las tablas de hooks y de MCP;
  - los pasos del CI;
  - el Ruleset.
- **S5.** En el sandbox, «CI y hooks locales» va a `docs/java.md` y la parte descriptiva de
  «Gobernanza» (el Ruleset exige CI y 1 aprobación) a `docs/infrastructure.md`. El sandbox recibe
  en `infrastructure.md` **su propia** tabla de 6 hooks, porque su `java.md:146` ya enlaza a un
  ancla que no existe.
- **S6.** Lo que se mueve se re-verifica al moverlo. Hoy la tabla dice «7 hooks»: el monorepo
  registra 8 (se sumó `block-dangerous-powershell.sh`) y el sandbox 6. «Pendiente de aprobación»
  del MCP es un estado de cada máquina: se reescribe como el paso de aprobar el workspace.
- **S7.** La demostración de que la regla muerde se corre en local y no se commitea.
- **S8.** El sandbox va en un worktree desde `origin/dev` (el clon principal tiene un cambio ajeno
  en `Recuperador.java`), en un solo commit (el sandbox mergea por squash), con PR a su `dev` abierta
  por esta corrida después de la del monorepo. Referencias siempre como
  `G3A/workshop-desarrollo-sw-guiado-por-ia#120`, nunca `#120` suelto ni `Closes`.

## Pasos — monorepo (`base-conocimiento/`)

Todos los commits llevan `Refs #120` (el último, `Closes #120`) y el párrafo final de trailers:
`Asistido-por-IA: claude-opus-5`, `Co-Authored-By` y `Claude-Session`.

1. **Prueba primero** (`src/test/java/co/g3a/baseconocimiento/ArquitecturaTest.java`, un solo
   Edit): en `compartidoEsHoja`, `.allowEmptyShould(true)` pasa a `false`, con un comentario como el
   de las otras reglas.
   - **RED:** apunto la regla a `RAIZ + ".compartid0.."`. Con `true`,
     `sh ./mvnw -q -Dtest=ArquitecturaTest#compartidoEsHoja test` sale verde (el defecto); con
     `false`, rojo («failed to check any classes»).
   - **GREEN:** restauro el paquete real con `false` y sale verde. Además, un import temporal de
     `llm` en `compartido` hace fallar la regla, y después se revierte.
   - Todo corre desde `base-conocimiento/`, con `JAVA_HOME` apuntando al JDK 25 de AppData.
   - Commit: `test(base-conocimiento): compartidoEsHoja muerde, ya no nace verde por vacia`.
2. **`docs/architecture.md:48`** — `compartido` = records y un enum anidados en
   `compartido/Dominio.java` (`ProyectoId`, `Pregunta`, `IdiomaRespuesta`, `Filtros`, `Fragmento`,
   `Cita`, `Respuesta`), sin lógica de negocio. El texto queda idéntico en los dos repos.
   **`docs/java.md`:**
   - l.26-27: «solo vocabulario (`compartido/Dominio.java`)» + enlace a `architecture.md`, sin
     repetir la lista.
   - l.68: se quita el número de usos de `@Value`.
   - l.76-80: «`allowEmptyShould(false)` explícito en las seis `noClasses()`».
   - l.94-98: se quita el TODO y se listan las 7 `@ConfigurationProperties` con su prefijo,
     registradas con `@ConfigurationPropertiesScan` en `BaseConocimientoApplication`; un solo
     `application.yml`.
   - l.138-143: actuator expone `health,info,metrics` sin acotar por perfil; `.mcp.json` y los
     hooks viven en la raíz del monorepo, con enlace a `infrastructure.md`.
   - l.147: el enlace pasa a `infrastructure.md#agente-de-ia-hooks-y-mcp`.
   - «Build, run, test» / «Quality gates»: absorben de AGENTS.md lo que falte:
     - `-Werror -Xlint:all`;
     - gitleaks solo en CI (el `lefthook.yml` del monorepo no lo corre);
     - Spotless sin `ratchetFrom`;
     - Checkstyle con `includeTestSourceDirectory`;
     - la lista real de WireMock.

     La historia de las 35 violaciones no se mueve: ya está enlazada.
   - Commit: `docs(base-conocimiento): java.md y architecture.md al dia con compartido, propiedades, actuator y .mcp.json`.
3. **`docs/infrastructure.md`:**
   - CI/CD pasa a describir el job `check`:
     - Herramienta: GitHub Actions, `.github/workflows/ci.yml` en la raíz del monorepo con
       `working-directory: base-conocimiento`.
     - Trigger: push a cualquier rama y PR desde forks.
     - Pasos: gitleaks 8.30.1, JDK 25, `make ci` (lint, build, test, secretos) y los reportes de
       Surefire como artefacto.
     - Una línea: «el mismo workflow verifica otras piezas del monorepo (playbook, CHANGELOG del
       plugin)».
     - **No hay CD**: producción sigue siendo `make up` a mano.
   - «Agente de IA (MCP)» pasa a «Agente de IA (hooks y MCP)»: tabla de hooks re-verificada (8
     scripts; la fila de comandos peligrosos cubre Bash y PowerShell), tabla MCP, paso de aprobación
     del workspace y nota de Context7.
   - Se corrige el DSN `jdbc:postgresql://…` a
     `postgres://kb:kb@localhost:5432/baseconocimiento?sslmode=disable`.
   - Commit: `docs(base-conocimiento): infrastructure.md describe el CI real y recibe hooks y MCP`.
4. **`AGENTS.md` ≤ 80 líneas**, con presupuesto:
   - apertura: 2;
   - Dónde encontrar: ~15;
   - Comandos: ≤ 8 + 2 de `make help`;
   - Reglas no obvias: ~28, las 7 viñetas actuales compactadas (`SHELL` en 3, JDK 25 en 2) + la
     viñeta de «viven en la raíz»;
   - Pruebas, Estilo, CI, Hooks y MCP: 2 líneas cada una (encabezado y enlace);
   - Seguridad: ~6 (jqwik en 2).

   Verificación:
   - `wc -l` ≤ 80;
   - la lista de reglas antes y después, con las mismas viñetas;
   - `grep` en `docs/` de cada hecho que salió;
   - los enlaces relativos resuelven.

   Commit: `docs(base-conocimiento): AGENTS.md vuelve a ser un indice`.
5. **`docs/claims-ledger.md`:**
   - La cabecera pasa a la fecha 2026-09-14 (#120) con el conteo nuevo.
   - Filas vigentes nuevas:
     - `compartido` = 7 tipos;
     - 7 `@ConfigurationProperties`;
     - `.mcp.json` y 8 hooks en la raíz;
     - `compartidoEsHoja` con `false`.
   - La fila «5 pruebas» pasa a «Invalidadas» (hoy 6/5/6), y se corrige la celda que dice «solo
     `compartidoEsHoja` conserva `true`».
   - Nueva nota «docs que contradecían filas vigentes»: el CI y actuator ya estaban bien en el
     ledger, pero no en los docs. Lección: el ledger no se propaga solo a los docs.
   - Commit con `Closes #120`.
6. **Gates:** `make check` (lo corre el pre-push; lanzarlo en segundo plano); `/code-review medium
   <merge-base>..HEAD`. Push y PR a `dev`, con título `docs(base-conocimiento): …`, y en el cuerpo
   `Closes #120`, la verificación real y el trailer.

## Pasos — sandbox (worktree desde `origin/dev`, rama `docs/afirmaciones-desactualizadas-espejo-120`)

7. **Antes:** comparar por hash de blob `git ls-tree -r origin/dev:base-conocimiento` (monorepo)
   contra `git ls-tree -r origin/dev` (sandbox), con la lista completa de excepciones
   (`repos-espejo`).
8. **Cambios, cada uno sobre la redacción del sandbox:**
   - `ArquitecturaTest.java` y `architecture.md` idénticos al monorepo, por hash.
   - `java.md`: las mismas correcciones, más l.140-142 («llega en F2»: el sandbox ya tiene
     `.mcp.json` y 6 hooks) y l.146 (ancla), más «CI y hooks locales» (Lefthook con gitleaks en
     pre-commit **y** CI).
   - `infrastructure.md`: su CI (un job, `ci.yml` en la raíz, sin `working-directory`), su tabla de
     6 hooks, el Ruleset y el DSN.
   - `AGENTS.md` ≤ 80, con el mismo esquema de encabezados y enlaces.
   - `claims-ledger.md`: l.25 y l.41 («5 pruebas»), la cabecera y las filas nuevas.

   Verificación:
   - `make check` en el worktree;
   - comparar hashes después;
   - un commit, PR a `dev` del sandbox citando la PR del monorepo.

   Después se edita el cuerpo de la PR del monorepo para citar la del sandbox.
9. **CI en verde** en las dos PR; comentarios atendidos. Antes de cada push extra:
   `gh pr view --json state`, porque el usuario mergea rápido.
10. Comentario final en #120 y Status del Project a «In review». `Closes` cierra el issue cuando
    `dev` llega a `main`, no al mergear en `dev`.

## Riesgos

- Spotless borra imports sin usar, así que el cambio de `ArquitecturaTest` y el import temporal van
  en un Edit cada uno. El pre-push tarda más de 2 minutos.
- Los planes históricos del sandbox citan «`AGENTS.md` § Gobernanza», y el ADR-0012 enlaza a un
  gotcha que ya no existe: son registro histórico y no se tocan.

## Fuera de alcance (se menciona en la PR)

- El contenido de #114.
- El TODO de topología de producción.
- El Javadoc de `Dominio.java` («ocho archivos» para 7 tipos).
- La recomendación `gh pr merge --admin` del sandbox.
- Alinear `instrument-agent-java`/`instrument-project-java` con el techo de ~80 de
  `agent-context-java` (candidato para el paso K).

## Qué marcó cada lente y cómo se resolvió

- **Convenciones:**
  - `architecture.md:48` duplicado → paso 2.
  - Las skills esperan encontrar Hooks y MCP en `AGENTS.md` → S3, con los encabezados de una línea.
  - Faltaban trailers y `Refs` → todos los commits.
  - Referencias cruzadas en el sandbox → S8.
  - Reglas escondidas en las secciones que salen → S4.
  - Ledger: sin duplicar filas y con la cabecera → paso 5.
  - CI de otras piezas → una línea.
  - Las 35 violaciones no se mueven.
  - Comparar por hashes antes y después → pasos 7 y 8.
- **Corrección:**
  - 8/6 hooks → S6.
  - Ancla rota en el sandbox → paso 8.
  - DSN `jdbc:` → paso 3.
  - CI y lefthook distintos entre repos → pasos 2 y 8.
  - «seis `noClasses()`» y 22 `@Value` → paso 2.
  - WireMock → S2.
  - La prueba sí demuestra el defecto; se suma el import temporal para probar que muerde.
- **Alcance:**
  - Presupuesto de líneas → paso 4.
  - Mover sin re-verificar → S6.
  - S4 y S5 del sandbox quedan como supuestos explícitos acá.
  - Orden y referencias entre las dos PR → pasos 8 y 9.
  - Descartado: abrir un issue aparte para alinear las skills. La skill no escribe en el tracker
    fuera de #120, así que queda propuesto para el paso K.
