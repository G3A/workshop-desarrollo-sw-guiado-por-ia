# Instrumentación Java con IA

Un plugin de Claude Code, `sdlc-ia`, con cuatro skills que instrumentan un repositorio Java/Spring
para que un agente de código con IA pueda trabajar en él con las mismas garantías que un equipo
humano exigiría: contexto legible, controles deterministas que se prueban fallando antes de
reportar éxito, límites explícitos sobre qué puede hacer el agente solo, y un ciclo completo de
ticket → plan → build → PR verificada sobre GitHub.

Sigue las convenciones de un plugin público real y maduro,
[`ArkandiaLabs/arkandia-skills`](https://github.com/ArkandiaLabs/arkandia-skills) (MIT): mismo
formato de `SKILL.md`, misma mecánica de verificación, mismo patrón de tests — pero es un
**plugin propio** (`sdlc-ia`, sin ninguna marca externa), enfocado en Java/Spring/Maven y en
GitHub en vez de Azure DevOps/Linear. `base-conocimiento/`, el proyecto Java real de este mismo
monorepo, es el caso de uso concreto detrás de cada decisión de diseño.

Sobre el alcance de `arkandia-skills` upstream: sus tres skills de instrumentación
(`agent-context-dotnet`, `instrument-project-dotnet`, `instrument-agent-dotnet`) siguen siendo
específicas de .NET. Sus skills de entrega (`linear-plan-build`, `ado-plan-build`) y la más
reciente, `requirement-to-spec`, ya son agnósticas de stack — funcionan sobre cualquier
repositorio. Decir sin más que "hoy solo cubre .NET" ya no describe el plugin completo.

## Las 6 skills

| Skill | Qué hace |
|---|---|
| [`agent-context-java`](docs/skills/agent-context-java-es.md) | Genera el paquete de contexto de un repo Java/Spring (`AGENTS.md`, `docs/architecture.md`, ADRs, `docs/java.md`) para que un agente de IA lo entienda sin adivinar. |
| [`instrument-project-java`](docs/skills/instrument-project-java-es.md) | Instala 8 controles deterministas: build reproducible, build estricto, estilo, un solo punto de entrada, hooks de pre-commit/pre-push, escaneo de secretos, pruebas de arquitectura (ArchUnit) y CI. |
| [`instrument-agent-java`](docs/skills/instrument-agent-java-es.md) | Registra servidores MCP y una catálogo de 8 hooks de Claude Code (bash puro, sin Node/jq) que limitan lo que el agente puede hacer solo. |
| [`github-plan-build`](docs/skills/github-plan-build-es.md) | El ciclo completo: toma un issue de GitHub, arma un plan, lo implementa test-first, y abre una PR verificada. |
| [`debt-triage`](docs/skills/debt-triage-es.md) | Triaja con criterio los hallazgos que un analizador estático ya reportó (Sonar, CodeQL, Checkstyle...) — nunca instala un sensor nuevo ni aplica un auto-fix a ciegas. |
| [`legacy-test-harness`](docs/skills/legacy-test-harness-es.md) | Acondiciona un repo legacy y hace crecer pruebas reales en 5 capas sobre código que ya está en producción, mapeando costuras al estilo Feathers antes de tocar nada. |

## Alcance deliberado

Esta es la versión **pública, sin marca de ninguna empresa**, centrada en GitHub y GitHub
Actions — así lo declara `proceso-operacional-con-ia/comandos.json`. Lo que no incluye no es un
olvido, es la frontera de ese alcance:

- **Sin gestión de trabajo jerárquica al estilo Azure Boards** (PBI/Task/Bug con iteraciones y
  cycle time) — `github-plan-build` trabaja contra GitHub Issues, un modelo plano.
- **Sin memoria semántica entre sesiones** — lo único que un ciclo nuevo "recuerda" del anterior
  es lo que quedó escrito en `AGENTS.md`.
- **Sin un panel de agentes especialistas por stack** (uno por framework de frontend, uno por
  base de datos, etc.) — la instrumentación Java vive en skills genéricas por función, no en
  agentes-personaje.

Un ecosistema privado con esas tres capas existe fuera de este repositorio; portarlas acá
significaría dejar de ser la versión pública y sin marca que este paquete se propone ser.

## Cómo instalarlo localmente

Desde una sesión de Claude Code, agregá este directorio como marketplace local y instalá el plugin:

```
/plugin marketplace add D:\GitHub_public\workshop-desarrollo-sw-guiado-por-ia\instrumentacion-java-ia
/plugin install sdlc-ia
```

Las 6 skills quedan disponibles como `/sdlc-ia:agent-context-java`,
`/sdlc-ia:instrument-project-java`, `/sdlc-ia:instrument-agent-java`,
`/sdlc-ia:github-plan-build`, `/sdlc-ia:debt-triage` y `/sdlc-ia:legacy-test-harness`.

## Verificar los hooks

Los scripts de `sdlc-ia/skills/instrument-agent-java/templates/hooks/` tienen su propia suite de
regresión, agnóstica de Java (bash puro, sin dependencias más allá de `bash`/`sed`/`awk`/`git`):

```bash
bash tests/run.sh          # resumen
VERBOSE=1 bash tests/run.sh  # cada caso
```

Esta suite prueba los scripts *tal como se entregan* — no un hook ya instalado en un repo. La
verificación de que un hook instalado dispara de verdad en un repo real es parte de la propia
skill `instrument-agent-java` (su fase de "probar antes de reportar éxito").

## Qué es distinto de `arkandia-skills`

- Nombre e identidad propios (`sdlc-ia`), no un fork literal — no está pensado para proponerse
  como PR al repo upstream sin un paso de rebautizo.
- Los `SKILL.md` van en inglés (misma convención upstream, es lo que Claude Code carga). Los
  templates que terminan en el repo del usuario (`AGENTS.md`, `architecture.md`, etc.) son
  bilingües como en `arkandia-skills` (`templates/es/` y `templates/en/`), con español como
  default porque este monorepo y `base-conocimiento` están en español.
- `github-plan-build` no existe en ningún stack de `arkandia-skills` todavía — es una skill nueva,
  no una adaptación.
