# Instrumentación Java con IA

Un plugin de Claude Code, `sdlc-ia`, con cuatro skills que instrumentan un repositorio Java/Spring
para que un agente de código con IA pueda trabajar en él con las mismas garantías que un equipo
humano exigiría: contexto legible, controles deterministas que se prueban fallando antes de
reportar éxito, límites explícitos sobre qué puede hacer el agente solo, y un ciclo completo de
ticket → plan → build → PR verificada sobre GitHub.

Sigue las convenciones de un plugin público real y maduro,
[`ArkandiaLabs/arkandia-skills`](https://github.com/ArkandiaLabs/arkandia-skills) (MIT, hoy solo
cubre .NET): mismo formato de `SKILL.md`, misma mecánica de verificación, mismo patrón de tests —
pero es un **plugin propio** (`sdlc-ia`, sin ninguna marca externa), enfocado en Java/Spring/Maven
y en GitHub en vez de Azure DevOps/Linear. `base-conocimiento/`, el proyecto Java real de este
mismo monorepo, es el caso de uso concreto detrás de cada decisión de diseño.

## Las 4 skills

| Skill | Qué hace |
|---|---|
| [`agent-context-java`](docs/skills/agent-context-java-es.md) | Genera el paquete de contexto de un repo Java/Spring (`AGENTS.md`, `docs/architecture.md`, ADRs, `docs/java.md`) para que un agente de IA lo entienda sin adivinar. |
| [`instrument-project-java`](docs/skills/instrument-project-java-es.md) | Instala 8 controles deterministas: build reproducible, build estricto, estilo, un solo punto de entrada, hooks de pre-commit/pre-push, escaneo de secretos, pruebas de arquitectura (ArchUnit) y CI. |
| [`instrument-agent-java`](docs/skills/instrument-agent-java-es.md) | Registra servidores MCP y una catálogo de 7 hooks de Claude Code (bash puro, sin Node/jq) que limitan lo que el agente puede hacer solo. |
| [`github-plan-build`](docs/skills/github-plan-build-es.md) | El ciclo completo: toma un issue de GitHub, arma un plan, lo implementa test-first, y abre una PR verificada. |

## Cómo instalarlo localmente

Desde una sesión de Claude Code, agregá este directorio como marketplace local y instalá el plugin:

```
/plugin marketplace add D:\GitHub_public\workshop-desarrollo-sw-guiado-por-ia\instrumentacion-java-ia
/plugin install sdlc-ia
```

Las 4 skills quedan disponibles como `/sdlc-ia:agent-context-java`,
`/sdlc-ia:instrument-project-java`, `/sdlc-ia:instrument-agent-java` y
`/sdlc-ia:github-plan-build`.

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
- Los `SKILL.md` van en inglés (misma convención upstream, es lo que Claude Code carga), pero los
  templates que terminan en el repo del usuario (`AGENTS.md`, `architecture.md`, etc.) están solo
  en español — este monorepo y `base-conocimiento` están en español, y `arkandia-skills` ya
  resuelve el caso bilingüe si algún día hace falta agregar `templates/en/`.
- `github-plan-build` no existe en ningún stack de `arkandia-skills` todavía — es una skill nueva,
  no una adaptación.
