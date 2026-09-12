# instrument-agent-java

## Qué es

Instala la capa de instrumentación **que mira hacia el agente** en un repositorio Java/Maven: qué
sistemas puede alcanzar (servidores MCP en `.mcp.json`) y qué no puede pasar por alto (un catálogo
de hooks de Claude Code en `.claude/settings.json`, respaldados por scripts de shell). Es el
complemento de `instrument-project-java`, que mira hacia el código: los sensores que corren sobre
el repositorio (build, estilo, arquitectura, CI) esté o no abierto un agente.

Las dos mitades que instala caen a lados opuestos del eje de la instrumentación. Un control es
determinista solo si el disparo **y** la decisión quedan fuera del razonamiento del modelo:

- **Los servidores MCP son no deterministas.** El modelo decide cuándo llamar una herramienta, y
  con qué argumentos. Agregan capacidad; no la limitan.
- **Los nueve hooks `type: command` son deterministas.** El ciclo de vida del agente los dispara en
  un punto fijo (`PreToolUse`, `PostToolUse`, `SessionStart`…) y un script de shell —no el
  modelo— decide si permite, bloquea o solo reporta. Por eso los hooks son un límite y MCP no.

Es el mismo eje que la leyenda del visor `proceso-operacional-con-ia`, que marca uno por uno los
nueve hooks como deterministas y los servidores MCP como no deterministas. Llamar a esta skill
«la capa no determinística», como decía antes, describe mal la mitad de lo que instala.

## Cómo se invoca

```
/sdlc-ia:instrument-agent-java
```

No recibe argumentos.

## El catálogo de nueve hooks

Primero amplía las capacidades del agente (MCP), después le pone límites (hooks) — en ese
orden, porque MCP solo agrega capacidad y los hooks la quitan. **El hook 9 y las reglas de permiso
son la segunda mitad de ese orden**, la que faltaba: hasta ahora la skill registraba los servidores
que amplían el alcance del agente y se negaba a configurar el único mecanismo determinista que lo
acota.

| # | Hook | Qué bloquea | Por defecto |
|---|------|-------------|-------------|
| 1 | Guardia de lectura de secretos | Sí — impide leer `.env`, claves privadas, `secrets.json`, bajo Bash, PowerShell o `Read` | Activado |
| 2 | Formateo al editar | No — corre Spotless sobre el archivo recién editado | Activado |
| 3 | Bloqueo de comandos peligrosos (Bash) | Sí — `rm -rf` fuera del repo, `sudo`, force-push a ramas protegidas | Ofrecido |
| 4 | Barrido de dependencias | No — solo reporta desactualizadas o vulnerables al iniciar sesión | Ofrecido |
| 5 | Registro de auditoría | No — registra cada llamada a herramienta | Ofrecido |
| 6 | Guardia de versión centralizada | No — advierte si una dependencia nueva fija su propia versión | Ofrecido, solo si el POM ya usa `<dependencyManagement>` o un BOM |
| 7 | Guardia de migraciones generadas | Sí — impide editar una migración de Flyway/Liquibase ya aplicada | Ofrecido, solo si el repositorio tiene migraciones Flyway o Liquibase |
| 8 | Bloqueo de comandos peligrosos (PowerShell) | Sí — `Remove-Item -Recurse` fuera del repo, `runas`, force-push, `mvn deploy`, con su propio tokenizador | Ofrecido, solo si el equipo usa Windows |
| 9 | Guardia de escrituras por MCP | Sí — una llamada que apunta a otro repositorio, o una sentencia que escribe en la base | Ofrecido, solo si hay algún servidor MCP registrado |

## Las reglas de permiso sobre MCP

**Dos mecanismos, cada uno donde rinde**, con el mismo reparto que la skill ya hace entre los hooks
de Git y los del agente:

| | Escribe en | Decide sobre | Bueno para |
|---|---|---|---|
| Reglas `permissions` | `.claude/settings.json` | El **nombre** de la herramienta | La postura general, legible de un vistazo y auditable en un diff |
| Hook 9 | `scripts/agent-hooks/mcp-write-guard.sh` | Los **argumentos** | Lo que un nombre no puede expresar: qué repositorio, qué tabla, qué rama |

**La postura por defecto es negar escrituras y permitir lecturas.** Ordena por consecuencia y no
por servidor: leer un issue no cambia nada, cerrarlo sí. Se descartaron dos alternativas:

- **`ask` para todo `mcp__*`** es seguro y agotador. El agente se detiene en cada lectura de issue
  y a los dos días alguien permite todo para poder trabajar. Un gate que se apaga protege menos que
  uno que nunca se instaló.
- **Fiarse del `readOnlyHint` del servidor** no es opción: la especificación obliga a tratar las
  anotaciones como no confiables, porque las declara el mismo servidor al que querrías vigilar.

**No rompe `github-plan-build`.** La lista de negación parece que frenaría el ciclo de entrega
—abre PRs, comenta issues, mueve etiquetas—, y no lo hace: esa skill va por la CLI `gh` sobre Bash,
no por el servidor MCP de GitHub, así que ninguno de esos matchers le aplica. La skill lo dice en el
reporte, porque sin esa frase la primera persona que lea la lista apaga el control entero para
desbloquear algo que nunca estuvo bloqueado.

### Por qué se levantó una regla dura

Hasta esta versión, la skill declaraba que **nunca** tocaba `permissions`. Era incoherente consigo
misma por dos lados: su propio `references/hook-catalog.md` ya le dice al usuario que cierre un
hueco «con una regla de negación en permissions, no con un hook», y su orden declarado —MCP
primero, hooks después— quedaba a medio ejecutar.

La regla no desapareció, se acotó: se escribe **solo con confirmación explícita** en la pregunta 6
del alcance, y **solo matchers `mcp__*`**. Todo lo demás de esa clave sigue siendo del usuario y no
se toca nunca. Si ya hay reglas `mcp__*`, no las sobrescribe: reporta la diferencia y se detiene.

## El servidor de navegador, la cuarta capa de verificación

Es **la única capa donde el agente no podía comprobar su propio trabajo**. Las otras tres —local,
CI y seguridad— las corre y las lee. La cuarta, «¿se ve como debería?», la declaraba terminada sin
haberla mirado nunca.

**Se ofrece solo si el descubrimiento encuentra interfaz** en el repositorio: plantillas de
Thymeleaf/JTE/Freemarker, recursos estáticos, un subproyecto de frontend, controladores que
devuelven vistas, o una suite de pruebas de navegador que ya exista. Es la misma regla que esconde
tres de los nueve hooks: no se ofrece un control para un artefacto que el repositorio no tiene, y
en un servicio REST puro un MCP de navegador es peso muerto que se paga en contexto cada sesión.
**El resultado negativo se reporta**, con lo que se buscó — no se salta en silencio.

**Cuál de los dos, se pregunta.** No se fija uno por defecto, porque responden preguntas distintas:

| | Playwright MCP | Chrome DevTools MCP |
|---|---|---|
| Lo mantiene | Microsoft, mismo repositorio y licencia que Playwright | El equipo de Chrome DevTools, en Google |
| La pregunta que contesta | «¿funciona y se ve como debería?» | «¿por qué va lento, o por qué falló esa petición?» |
| Cómo lee la página | **Árbol de accesibilidad por defecto**, no imágenes | Trazas de rendimiento, peticiones de red, consola con stack traces mapeados |

Dos datos que cambian la respuesta y por eso van en la pregunta: **Playwright MCP lee estructura,
no capturas** —más barato y más fiable para hacer clic en lo correcto, pero «¿se ve bien?» necesita
pedir una captura explícita—, y **ninguno de los dos necesita un navegador instalado antes**.
Tampoco son razón para agregar Playwright a las dependencias del repositorio: elegirle el stack de
pruebas a un equipo no es decisión de esta skill.

### Lo que registrar el servidor **no** compra

La documentación de Playwright MCP lo dice de su propio producto: las listas de orígenes
(`--allowed-origins`, `--blocked-origins`) y la protección de acceso a archivos son **«defensas de
conveniencia para atrapar accesos no intencionales, no una frontera de seguridad»** — no impiden
redirecciones y se pueden sortear a propósito. **La aislación real exige permisos del lado del
cliente.**

Es palabra por palabra el eje de esta skill, dicho por el proveedor: **MCP solo agrega capacidad;
los hooks y `permissions` son lo único que la quita.** Un servidor de navegador es justamente el
caso que vuelve obligatorias las reglas `mcp__*`, no un adorno. La skill igual pasa `--isolated`
—el perfil queda en memoria, no en disco, para que el agente no herede sesiones ya iniciadas— y
acota los orígenes a los del propio sistema, pero lo dice como lo que es.

**Y hay un segundo riesgo que no es de red:** un MCP de navegador mete **contenido de páginas web
en el contexto del modelo**. Es la superficie clásica de inyección de prompts — texto de una página
que ahora convive con tus instrucciones. Nada de esto lo resuelve; lo que sí se hace es mantener
el radio chico y decirlo en el reporte.

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
   servidores MCP habilitar (GitHub, Context7, DBHub y un servidor de navegador — solo si hay evidencia de que aplican, y cuál de los dos de navegador), qué
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
  `version-pin-guard.sh`, `generated-files-guard.sh`, `block-dangerous-powershell.sh`,
  `mcp-write-guard.sh`) — los nueve de la tabla de arriba.
- `.claude/settings.json` — la clave `hooks`, y la clave `permissions` **solo con confirmación
  explícita y solo con matchers `mcp__*`**. Nunca escribe en `.claude/settings.local.json`.
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
