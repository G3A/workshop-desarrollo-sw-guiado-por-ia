# Acta de la IA

El registro de lo que hizo la IA en una sesión de Claude Code, según el
[ADR-0005](../../docs/adrs/0005-re-ejecutar-el-registro-de-la-ia-sin-el-modelo.md) y el
[modelo conceptual](../../docs/modelo-conceptual-registro-ia.md). Esta carpeta trae la primera
pieza, el **acta cruda** (#216), la **curada** que se deriva de ella (#218), el **índice de la
tarea**, el **motor** que re-ejecuta la curada en Docker, la **conformidad** con los pasos que
las skills marcan, el trailer `Registro-IA:` con su sensor del CI, la exportación a OCEL 2.0 y
PROV-O y el **visor** para auditorías (#222).

## Cómo funciona

1. **Captura.** `capturar.mjs` corre como hook en `SessionStart`, `PreToolUse`, `PermissionRequest`,
   `PostToolUse`, `PostToolUseFailure` y `SessionEnd`; está registrado en `.claude/settings.json`.
   Agrega una línea a `.ia/captura/<sesión>.jsonl` con el `HEAD` y el hash del árbol antes y
   después de cada acción, y cada pedido de permiso. Las lecturas no capturan árbol, porque no
   lo cambian. Nunca bloquea la sesión. En cada evento solo carga `nucleo-captura.mjs`, que no
   importa nada del repo: en la sesión de #218 una acción dejó `curar-acta.mjs` con un error de
   sintaxis, el hook lo importaba y no cargó hasta que otra acción lo arregló, y cuatro acciones
   quedaron sin captura (#222).
2. **Compilación.** Al cerrar la sesión, el mismo hook llama a `compilar-acta.mjs`, que lee el
   transcript principal, los de los subagentes y la captura, y escribe
   `.ia/registros/<tarea>/<sesión>.acta.cruda.jsonl`: un acta por tarea que la sesión tocó.
3. **Verificación.** El acta no se escribe si rompe una invariante del modelo (`validar-acta.mjs`)
   o si gitleaks encuentra un posible secreto. Sin gitleaks en el PATH tampoco se escribe.
4. **Curación.** Por cada acta escrita, el hook llama a `curar-acta.mjs`, que deja
   `<sesión>.acta.curada.jsonl` junto a ella. Si la curación se detiene, lo avisa en stderr y la
   cruda queda escrita. Medido en la sesión de #218 (203 acciones): unos 12 s compilar y 15 s
   curar y verificar. El hook tiene 300 s en `.claude/settings.json`, para que una sesión de unas
   mil acciones también alcance; si se pasa, Claude Code lo corta y solo falta la curada.
5. **Índice.** Por último, `indexar-tarea.mjs` reescribe `.ia/registros/<tarea>/indice.json`
   con las actas de la tarea —sesión, inicio, fin, ramas, sha256 de la cruda y si tiene
   curada— y el índice inverso: por cada archivo que la tarea cambió, qué acciones lo cambiaron,
   de qué sesión y de qué agente (PC-02 y PC-03). Sale de las crudas, así que también lista los
   fallos con residuo, los efectos de hook y los subagentes descartados. Es determinista y no
   lleva la hora en que se indexó.

El motor no corre en el hook: se llama a mano, y en la fase 3 lo llamará el CI.

`.ia/` está en `.gitignore`, pero las actas se versionan de a una (ADR-0006): ver «Versionar el
acta», más abajo.

## A mano

```text
node scripts/acta/compilar-acta.mjs --transcript <sesión.jsonl> [--salida <carpeta>]
node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> [--salida <acta.curada.jsonl>]
node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> --comprobar <acta.curada.jsonl>
node scripts/acta/validar-acta.mjs <acta.cruda.jsonl | acta.curada.jsonl>
node scripts/acta/indexar-tarea.mjs <.ia/registros/<tarea>>
node scripts/acta/reejecutar-acta.mjs <acta.curada.jsonl> [--reporte <archivo>] [--tiempo <s>]
node scripts/acta/conformidad.mjs <acta.cruda.jsonl | acta.curada.jsonl>
node scripts/acta/registrar-sesion.mjs [--sesion <id>] [--sin-stage]
node scripts/acta/verificar-registro-ia.mjs --rango <A..B> [--cuerpo-pr <archivo>] [--sin-motor]
node scripts/acta/deriva-huella.mjs <acta> [--hasta <commit>] [--modelo <id>]
node scripts/acta/exportar-acta.mjs <acta> --formato ocel|prov [--salida <archivo>]
node scripts/acta/visor-acta.mjs <acta.curada.jsonl> [--reporte <r.json>] [--sin-red] [--salida <f>]
node --test scripts/acta/pruebas/compilar-acta.test.mjs scripts/acta/pruebas/capturar.test.mjs
node --test scripts/acta/pruebas/curar-acta.test.mjs scripts/acta/pruebas/verificar-arbol.test.mjs
node --test scripts/acta/pruebas/indexar-tarea.test.mjs scripts/acta/pruebas/ejecutar-acta.test.mjs
node --test scripts/acta/pruebas/reejecutar-acta.test.mjs scripts/acta/pruebas/conformidad.test.mjs
node --test scripts/acta/pruebas/verificar-registro-ia.test.mjs
node --test scripts/acta/pruebas/deriva-huella.test.mjs scripts/acta/pruebas/exportar-acta.test.mjs
node --test scripts/acta/pruebas/visor-acta.test.mjs
```

`curar-acta.mjs` se corre desde dentro del repo: lee de él los blobs para verificar el árbol.
Con `--comprobar` vuelve a derivar la curada y falla si no coincide byte a byte (invariante 8).

La curada entra solo con las acciones exitosas, de todos los agentes, con su id de la cruda; los
pasos y turnos que quedan vacíos salen. Un fallo que cambió el árbol entra como acción `residuo`
con su diff, y uno sin árbol que pudo escribir detiene la curación (código 3). Una acción
cuyo código de salida Claude Code reinterpretó como benigno, como el 1 de un `| grep` que en
realidad era el fallo de un eslabón anterior, queda en la cruda con `exito` en null y la
interpretación en `codigoReinterpretado` (#219): en la curada se trata como un fallo. Cada acción
lista en `intentosPrevios` los fallos que la precedieron sobre el mismo archivo o comando. Lo que
un hook cambia entre dos acciones lo registra el compilador como acción `hook`, con su diff y el
agente `hook:sin-identificar`; la curación exige que la cadena de árboles no tenga huecos. Cada
subagente queda `integrado` o `descartado` según si lo que cambió sigue en el árbol final; uno
descartado sale de la curada, y si sin él la cadena se corta, la curación se detiene. Un comando
que termina con
`# rojo-esperado: <qué demuestra>` sale de la secuencia y va al anexo de verificaciones negativas,
al final de la curada: el motor no lo ejecuta. Antes de escribirla, `verificar-arbol.mjs` aplica
en memoria sus Edit y Write sobre los blobs del repo y comprueba que la cadena llegue al árbol de
`SessionEnd`; un Edit en cuya ventana escribió un hook se parte en dos. Sin repo no se cura.

## El motor

`reejecutar-acta.mjs` re-ejecuta una curada sin el modelo y escribe
`<sesión>.acta.reporte.json` junto a ella. El ADR-0005 lo nombra `scripts/reejecutar-acta.mjs`;
vive aquí con el resto del acta. Construye la imagen de `motor/Dockerfile` (Node, git y pwsh,
con la base fijada por digest) la primera vez, y es el único paso que sale a la red. Después
arma un pack de git con el `headBase` y todos los árboles del acta, y corre
`ejecutar-acta.mjs` en un contenedor con `--network none`, `--read-only`, usuario no root,
límites de memoria, CPU y procesos, y el árbol de trabajo en un tmpfs. No se monta ninguna
carpeta del host con escritura, ni el socket de Docker.

Cada acción recibe un veredicto (`igual`, `equivalente`, `diverge`, `no_verificable`) y el
modo en que se obtuvo:

| Modo | Qué acciones | Cómo se juzga |
|---|---|---|
| `aplicada` | Edit, Write, MultiEdit; los `hook` y `residuo`, con `git apply` de su diff | El árbol: `igual` o `diverge` |
| `ejecutada` | Bash con `bash`, PowerShell con `pwsh`, en el directorio en que corrió | Árbol y código 0: `igual` con la misma salida normalizada, `equivalente` con otra; si no, `diverge`. Un comando que no está en la imagen (127) queda `no_verificable` |
| `comparada` | Read | Sus líneas numeradas contra el archivo; las que difieren dan `equivalente` |
| `fixture` | `externa_lectura` | No se ejecuta: el árbol sale del registro. Siempre `no_verificable` |
| `omitida` | `efecto_externo` (invariante 9), envoltorios de subagente y lo no re-ejecutable | Siempre `no_verificable` |

No son re-ejecutables los comandos en segundo plano, los que usan la carpeta del usuario (`~`,
que no viaja al sandbox) y los de Windows PowerShell 5.1 que `pwsh` de Linux no tiene: el
registro de Windows, cmdlets como `Get-Service`, COM, `-Encoding Default`, rutas con letra de
unidad o un `.exe`. La lista está en `clasificar.mjs`.

Tras una divergencia, el motor restaura el árbol del registro y sigue: el reporte trae todas las
divergencias, cada una medida desde el árbol que la acción tuvo en la sesión, y
`primeraDivergencia` nombra la primera. El `resumen` separa las verificadas de las que salieron
del fixture, y `imagen` guarda el id de la imagen construida y si su Dockerfile es el que la
huella del acta registró en el `headBase`. Sale con 0 si la curada llega a su árbol final sin
divergencias, 1 si no, y 3 si no se pudo re-ejecutar, por ejemplo sin Docker.

Medido sobre la fase 1 del #222 (55 acciones, unos 26 s): 45 verificadas, las 18 ediciones
iguales y una divergencia legítima, un `cd` a una carpeta de `$TEMP` que había creado un
comando omitido.

## Versionar el acta

El [ADR-0006](../../docs/adrs/0006-el-acta-de-la-ia-se-versiona-en-git.md) decide que el acta va en
git, y cómo. El flujo de una PR asistida:

1. **Commitear el trabajo.** Dentro de Claude Code, el hook `commit-msg` (`citar-acta.mjs`)
   agrega `Registro-IA: .ia/registros/<tarea>/<sesión>.acta.curada.jsonl` debajo de
   `Asistido-por-IA:`. Usa `CLAUDE_CODE_SESSION_ID`, que Claude Code exporta a sus comandos.
   Fuera de Claude Code, el trailer se escribe a mano; sin acta, `Registro-IA: ninguno: <motivo>`.
2. **Registrar la sesión.** `node scripts/acta/registrar-sesion.mjs` compila lo que va de la
   sesión, cortando la llamada en curso, la cura, empaca sus objetos, reescribe el índice y deja en
   stage con `git add -f` la cruda, la curada, `<sesión>.acta.objetos.pack` y `indice.json`.
   **Antes de commitearlos, léelos:** este repo es público, y el acta trae prompts, salidas y
   contenido leído.
3. **Commitear el acta y abrir la PR**, con cada `Registro-IA:` repetido en el cuerpo: el merge a
   `dev` es por squash con ese cuerpo como mensaje.

El pack de objetos existe porque los árboles del acta los escribió la captura en la máquina de
quien trabajó, y ningún commit los alcanza. Lleva solo lo que el `headBase` no tiene: en la
sesión de este issue, 260 KB en vez de 8,7 MB.

En el CI, `verificar-registro-ia.mjs` falla si un commit asistido no declara su acta, si el acta
no está en la PR, si la curada no sale de su cruda (I8), si el commit deja un archivo con un
contenido que ningún árbol del acta tuvo, si el motor no reproduce una acción aplicada o si el
cuerpo de la PR no repite un trailer. Un comando ejecutado que diverge en el contenedor se avisa y
no falla (ADR-0006).

`deriva-huella.mjs` responde PC-14. Dice qué cambió desde el `headBase` del acta en los
instructivos, la versión del plugin y el Dockerfile del motor, y en la versión de Claude Code y
el modelo si se los pasa. Lo que no se puede saber queda en `null`.

El compilador redacta además el valor de toda variable cuyo nombre dice que es secreta
(`*TOKEN*`, `*SECRET*`, `*PASSWORD*`, `*API_KEY*`) en la forma `NOMBRE=valor`. Un `env`
dentro de Claude Code muestra `CLAUDE_CODE_MESSAGING_TOKEN`, y gitleaks no lo reconoce.

## Exportar a OCEL 2.0 y PROV-O

`exportar-acta.mjs` escribe el acta en [OCEL 2.0](https://www.ocel-standard.org) (JSON) o en
[W3C PROV-O](https://www.w3.org/TR/prov-o/) (JSON-LD), con la correspondencia de la sección 6 del
[modelo conceptual](../../docs/modelo-conceptual-registro-ia.md):

- **OCEL:** cada acción es un evento con el tipo de su herramienta, y cada intervención, un evento
  `intervencion`. Tarea, sesión, turno, paso, paso prescrito, decisión, archivo y agente son
  objetos, con relaciones calificadas.
- **PROV-O:** sesión, turno, paso y acción son `prov:Activity` anidadas. Los agentes son
  `prov:Person` o `prov:SoftwareAgent`, con `prov:actedOnBehalfOf`. La curada es un
  `prov:Bundle` que `prov:wasDerivedFrom` su cruda, identificada por su sha256.

**Sin pérdida:** cada nodo lleva su elemento del acta exacto en `registro`, y
`importarOcel` / `importarProv` lo reconstruyen byte a byte. La prueba lo comprueba con cada
escenario de la fábrica, crudo y curado.

El análisis entre actas queda para cuando haya unas 20: con menos, los patrones no significan nada
(ADR-0005).

## El visor

`visor-acta.mjs` escribe un HTML autocontenido —sin red ni fuentes externas— que muestra la
sesión como evidencia para una auditoría. Sigue el diseño E5, elegido entre mockups, y su fuente es
[`jerarquia-proceso-actividad-tarea.md`](../../docs/jerarquia-proceso-actividad-tarea.md):

- **Ficha de trazabilidad** de la acción seleccionada. Muestra el eje del trabajo (proceso,
  actividad, tarea, paso y acción, con quién responde en cada nivel) y, justo debajo, el eje de la
  documentación (manual, procedimiento, instructivo y registro), cada documento bajo el nivel que
  describe.
- **Eje del tiempo**: las 7 fases del ciclo de vida, con el gate de la fase en curso y, adentro, la
  sesión en el orden en que ocurrió.
- **Alcance de la verificación**: cuánto se re-ejecutó, aplicó o comparó, cuánto se tomó del
  registro sin verificar y cuánto no es reproducible. Sin reporte del motor
  (`<sesión>.acta.reporte.json`), todo dice «registrada, sin verificar».
- **La tarea paso a paso**, con el porqué de cada paso, los intentos fallidos, las intervenciones
  de la persona y, por acción, la verificación, la conformidad con el instructivo y el control de
  la información documentada (ISO 9001, 7.5.3).
- **Vistas de la tarea**: sesiones, archivos y quién los cambió, quién hizo cada cosa, y las pruebas
  que debían fallar.

Se lee en lenguaje común: «Turno 2», «Paso 3 de 5 · Triage each group», «Acción 9». Los ids del
archivo aparecen solo en el bloque «Así está en el archivo del acta», y una prueba lo exige. Todo el
contenido del acta entra a la página como datos y se pinta como texto: un comando grabado nunca se
interpreta como HTML. La página vive en `visor/visor.css` y `visor/visor.js`; el generador los
copia dentro del HTML.

**Lo que configura la empresa**, en `proceso.json`: el nombre del proceso, su dueño, el manual,
los gates de cada fase y la traducción de los pasos de cada skill. Lo que no está configurado se
muestra como un campo a completar, nunca inventado. La persona asignada y el título salen del
issue en GitHub (`gh issue view`); con `--sin-red` o `--asignado` no se consulta.

Las capturas de Playwright que menciona el ADR-0005 no se incrustan todavía: el acta no las
registra.

## Intervenciones

Lo que hizo una persona durante la sesión (PC-10). Cada tipo sale de un hecho que el transcript o
la captura registran, nunca de leer el texto:

| Tipo | De dónde sale |
|---|---|
| `comando_usuario` | Un comando con `!` |
| `interrupcion` | `[Request interrupted by user]` |
| `permiso_rechazado` | El rechazo de una herramienta que pidió permiso |
| `permiso_aprobado` | Un `PermissionRequest` en modo `default`, `acceptEdits` o `plan`, y la herramienta corrió después |
| `correccion` | Un rechazo en el que la persona escribió cómo seguir («the user said:»), el rechazo de una pregunta o un plan (`AskUserQuestion`, `ExitPlanMode`) y el prompt que sigue a una interrupción. Lleva lo que la persona dijo en `texto` |

`PermissionRequest` no trae `tool_use_id` (medido con una sonda en Claude Code 2.1.287): la
captura guarda el hash de la entrada en cada `PreToolUse` y en cada pedido, y el compilador
empareja el pedido con el último `PreToolUse` de la misma herramienta y la misma entrada. Así
funcionan también dos llamadas en paralelo. No se registra como intervención:

- el pedido que en modo `auto` resuelve el clasificador;
- el «haven't granted it yet» de una sesión sin persona, como `claude -p`.

Sobre las sesiones de este proyecto, los 5 «permisos rechazados» que registraba el compilador
eran 2 rechazos de permiso y 3 preguntas descartadas, que ahora son correcciones.

Los transcripts de Claude Code están en `~/.claude/projects/<proyecto>/<sesión>.jsonl`, y los de
sus subagentes en `<sesión>/subagents/`. Los comandos son los mismos en PowerShell y en bash.

## Lo que el acta cruda todavía no tiene

- **Eventos que la captura perdió.** Si una acción que corrió no tiene su `PreToolUse` o su
  `PostToolUse` en la captura, el compilador lo avisa y la deja con `capturada` en null, no en
  false, que diría que no corrió. La curación se detiene en el hueco: es el caso de la sesión de
  #218, que recompilada con #222 sigue sin curarse por las acciones a236 a a239. No hay de dónde
  recuperar sus árboles; la causa, el hook que no cargaba, ya no se repite.

- **Pasos del instructivo de casi todas las skills.** Solo `debt-triage` marca sus pasos
  ([protocolo](../../docs/protocolo-de-marcadores-de-paso.md)). En las demás, cada paso es el turno
  completo, con procedencia `ausente` (PC-04), la fase queda en `null` y
  `conformidad.mjs` responde «sin datos».
- **Permisos que resolvió otro hook.** Si un hook responde un `PermissionRequest` antes que la
  persona, el acta no lo sabe y lo cuenta como aprobación de la persona. En este repo ningún hook
  responde pedidos de permiso.
- **Un permiso aprobado con «no volver a preguntar».** Desde ahí la herramienta no pide permiso, y
  sus llamadas siguientes no dejan intervención: la persona ya decidió, una sola vez.
- **Árbol de los comandos con `!`.** Los hooks no corren para los comandos que escribe el
  usuario: su acción queda sin hash de árbol.
- **Fallos dentro de un pipe.** Sin `pipefail`, `a | tail` sale con el código de `tail`, y el
  fallo de `a` no deja rastro ni en el transcript ni en el payload de los hooks, que tampoco traen
  el código de salida crudo. Si importa, el comando se escribe con `set -o pipefail`.
- **El motor y la infraestructura.** No se monta el socket de Docker: lo que levanta
  contenedores o Testcontainers falla dentro del sandbox. La opción `--con-infra` del ADR-0005
  queda para cuando una sesión la necesite.
- **Huella sin captura.** Si la sesión no tiene captura (por ejemplo, una sesión anterior a este
  hook), no hay `HEAD` base, y el acta sale sin versión del plugin ni documentos de la huella.
