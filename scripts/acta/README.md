# Acta de la IA

El registro de lo que hizo la IA en una sesión de Claude Code, según el
[ADR-0005](../../docs/adrs/0005-re-ejecutar-el-registro-de-la-ia-sin-el-modelo.md) y el
[modelo conceptual](../../docs/modelo-conceptual-registro-ia.md). Esta carpeta trae la primera
pieza, el **acta cruda** (#216), la **curada** que se deriva de ella (#218), el **índice de la
tarea** y el **motor** que re-ejecuta la curada en Docker (#222). El trailer `Registro-IA:` y
el visor son las fases siguientes del #222.

## Cómo funciona

1. **Captura.** `capturar.mjs` corre como hook en `SessionStart`, `PreToolUse`, `PostToolUse`,
   `PostToolUseFailure` y `SessionEnd`; está registrado en `.claude/settings.json`. Agrega una
   línea a `.ia/captura/<sesión>.jsonl` con el `HEAD` y el hash del árbol antes y después de cada
   acción. Las lecturas no capturan árbol, porque no lo cambian. Nunca bloquea la sesión. En
   cada evento solo carga `nucleo-captura.mjs`, que no importa nada del repo: en la sesión de
   #218 una acción dejó `curar-acta.mjs` con un error de sintaxis, el hook lo importaba y no
   cargó hasta que otra acción lo arregló, y cuatro acciones quedaron sin captura (#222).
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

`.ia/` está en `.gitignore`: las actas pueden traer contenido leído durante la sesión, y qué se
versiona lo decide el issue del trailer.

## A mano

```text
node scripts/acta/compilar-acta.mjs --transcript <sesión.jsonl> [--salida <carpeta>]
node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> [--salida <acta.curada.jsonl>]
node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> --comprobar <acta.curada.jsonl>
node scripts/acta/validar-acta.mjs <acta.cruda.jsonl | acta.curada.jsonl>
node scripts/acta/indexar-tarea.mjs <.ia/registros/<tarea>>
node scripts/acta/reejecutar-acta.mjs <acta.curada.jsonl> [--reporte <archivo>] [--tiempo <s>]
node --test scripts/acta/pruebas/compilar-acta.test.mjs scripts/acta/pruebas/capturar.test.mjs
node --test scripts/acta/pruebas/curar-acta.test.mjs scripts/acta/pruebas/verificar-arbol.test.mjs
node --test scripts/acta/pruebas/indexar-tarea.test.mjs scripts/acta/pruebas/ejecutar-acta.test.mjs
node --test scripts/acta/pruebas/reejecutar-acta.test.mjs
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

Los transcripts de Claude Code están en `~/.claude/projects/<proyecto>/<sesión>.jsonl`, y los de
sus subagentes en `<sesión>/subagents/`. Los comandos son los mismos en PowerShell y en bash.

## Lo que el acta cruda todavía no tiene

- **Eventos que la captura perdió.** Si una acción que corrió no tiene su `PreToolUse` o su
  `PostToolUse` en la captura, el compilador lo avisa y la deja con `capturada` en null, no en
  false, que diría que no corrió. La curación se detiene en el hueco: es el caso de la sesión de
  #218, que recompilada con #222 sigue sin curarse por las acciones a236 a a239. No hay de dónde
  recuperar sus árboles; la causa, el hook que no cargaba, ya no se repite.

- **Pasos del instructivo.** Ninguna skill marca sus pasos, así que cada paso es el turno completo,
  con procedencia `ausente` (PC-04).
- **Fase.** La declara la skill, y ninguna la declara todavía: queda en `null`.
- **Permisos aprobados.** Solo se registran los rechazos que deja el transcript. Los hooks de
  permisos van en otro issue (PC-10).
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
