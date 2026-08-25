# instrument-agent-java

## Qué es

Instala la capa de **instrumentación no determinística** en un repositorio Java/Maven: los
controles cuyo motor es el criterio de un agente de IA, no un cálculo exacto. Configura qué
sistemas puede alcanzar el agente (servidores MCP en `.mcp.json`) y qué no puede pasar por alto
(un catálogo de hooks de Claude Code en `.claude/settings.json`, respaldados por scripts de
shell). Es el complemento de `instrument-project-java`, que instala los controles que una máquina
puede decidir sola en milisegundos.

## Cómo se invoca

```
/sdlc-ia:instrument-agent-java
```

No recibe argumentos.

## El catálogo de siete hooks

Primero amplía las capacidades del agente (MCP), después le pone límites (hooks) — en ese
orden, porque MCP solo agrega capacidad y los hooks la quitan.

| # | Hook | Qué bloquea | Por defecto |
|---|------|-------------|-------------|
| 1 | Guardia de lectura de secretos | Sí — impide leer `.env`, claves privadas, `secrets.json` | Activado |
| 2 | Formateo al editar | No — corre Spotless sobre el archivo recién editado | Activado |
| 3 | Bloqueo de comandos peligrosos | Sí — `rm -rf` fuera del repo, `sudo`, force-push a ramas protegidas | Ofrecido |
| 4 | Barrido de dependencias | No — solo reporta desactualizadas o vulnerables al iniciar sesión | Ofrecido |
| 5 | Registro de auditoría | No — registra cada llamada a herramienta | Ofrecido |
| 6 | Guardia de versión centralizada | No — advierte si una dependencia nueva fija su propia versión | Ofrecido, solo si el POM ya usa `<dependencyManagement>` o un BOM |
| 7 | Guardia de migraciones generadas | Sí — impide editar una migración de Flyway/Liquibase ya aplicada | Ofrecido, solo si el repositorio tiene migraciones Flyway o Liquibase |

## Fases principales

1. **Descubrimiento silencioso** — confirma que es un repositorio Java, ubica el o los POM
   (importante en un monorepo), revisa si ya hay hooks o servidores MCP configurados, examina el
   `Makefile` para saber qué comandos ya existen (`format`, `lint`, `audit`), verifica si Spotless
   está declarado y mide cuánto tarda en un archivo real, comprueba las precondiciones de cada
   hook (por ejemplo, si hay migraciones Flyway antes de ofrecer la guardia de migraciones),
   revisa datos de git (host remoto, rama por defecto, ramas de larga vida) y detecta qué base de
   datos usa el proyecto.
2. **Prerrequisitos** — verifica JDK/Maven y, crucialmente, si Git Bash está disponible en
   Windows, porque los scripts de los hooks son bash puro y, sin Git Bash, Claude Code cae a
   PowerShell y los hooks simplemente no hacen nada.
3. **Acordar el alcance** — pregunta solo lo que el descubrimiento no pudo resolver: qué
   servidores MCP habilitar (GitHub, Context7, DBHub — solo si hay evidencia de que aplican), qué
   hooks bloqueantes y cuáles de reporte activar (con checkboxes ya marcados para la guardia de
   secretos y el formateo al editar), si el registro de auditoría debe confirmarse
   explícitamente (porque graba el contenido completo de cada llamada) y qué ramas proteger.
4. **Aplicar** — confirma otra vez que el árbol de trabajo está limpio, escribe `.mcp.json`
   (fusionando, nunca reemplazando, y usando siempre `${VARIABLE_DE_ENTORNO}` en vez de una
   credencial literal), copia los scripts de hooks a `scripts/agent-hooks/`, los hace
   ejecutables y valida su sintaxis, y por último escribe `.claude/settings.json` agregando solo
   las claves nuevas sin tocar lo que ya había.
5. **Verificar rompiendo** — para cada hook instalado, lo dispara de verdad (por ejemplo,
   intentando leer un `.env` de prueba, o editando una migración existente) y confirma que
   efectivamente actúa y que el mensaje nombra el problema; después restaura todo a como estaba.
   MCP no se puede verificar de la misma forma porque los servidores quedan "pendientes de
   aprobación" hasta que el usuario confía en el workspace — ahí la skill solo confirma que el
   archivo es válido y dice explícitamente que esa mitad quedó escrita, no probada.
6. **Documentar y reportar** — actualiza `AGENTS.md` (secciones de hooks y de MCP) y `README.md`
   (prerrequisitos, variables de entorno, paso de confianza del workspace) si ya existen; no crea
   el paquete de documentación desde cero. Cierra con una tabla "pruébalo tú mismo": una línea por
   cada hook o servidor instalado, con qué pedirle al agente y qué se debería ver como resultado.

## Qué archivos toca o crea

- `.mcp.json` (fusionado con lo que ya exista).
- `scripts/agent-hooks/_lib.sh` y un script por cada hook instalado (`secret-read-guard.sh`,
  `format-on-edit.sh`, `block-dangerous-bash.sh`, `dependency-sweep.sh`, `audit-log.sh`,
  `version-pin-guard.sh`, `generated-files-guard.sh`).
- `.claude/settings.json` — únicamente la clave `hooks`; nunca toca `permissions` y nunca escribe
  en `.claude/settings.local.json`.
- `.gitignore` (agrega `logs/` antes de crear el registro de auditoría, para que no se publique
  por accidente).
- Secciones de `AGENTS.md`, `README.md`, `docs/infrastructure.md` y `docs/java.md`, si ya
  existen.

Nunca hace `commit` ni `push`: los únicos cambios de git son los de romper y restaurar durante la
verificación, deshechos antes de terminar.

## Decisiones de diseño a tener en cuenta

- **Los hooks son bash puro, sin depender de Node ni de `jq`.** Los scripts están escritos para
  bash 3.2 (sin arreglos asociativos, sin `mapfile`) y sin `jq`, porque esta herramienta no viene
  instalada por defecto ni en macOS ni en Windows. La extracción de campos se hace con `awk` y
  `sed`. Esto los hace portables, pero también significa que en Windows dependen de que Git Bash
  esté instalado — sin él, Claude Code cae a PowerShell y los hooks quedan inertes en silencio.
- **Los hooks no son un límite de seguridad real.** Corren con el shell y los permisos del
  usuario, y hacen coincidencia de texto, no de intención — la skill lo deja explícito en el
  reporte final.
- **Un hook que se activa en una acción legítima y cotidiana no es un sensor, es un error con una
  política pegada.** Por eso, por ejemplo, la guardia de versión centralizada solo se ofrece si el
  POM ya usa gestión centralizada de dependencias: sin eso, fijar una versión literal es la forma
  correcta de declarar una dependencia.
- **Fusiona, nunca reemplaza.** Tanto `.claude/settings.json` como `.mcp.json` suelen contener
  trabajo previo que no es de esta skill.
- **Se declara con claridad que los hooks y `.claude/settings.json` son exclusivos de Claude
  Code** — ningún otro agente los lee hoy. Los scripts en sí son shell portable y podrían
  reutilizarse, pero su registro no.
- **Cada versión se resuelve y se fija, nunca queda "flotante".** Por ejemplo, un servidor MCP
  vía `npx` sin versión fijada se resuelve de nuevo en cada sesión, así que un `.mcp.json`
  versionado terminaría ejecutando mañana un código que nadie revisó hoy.
