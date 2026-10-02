# Acta de la IA

El registro de lo que hizo la IA en una sesión de Claude Code, según el
[ADR-0005](../../docs/adrs/0005-re-ejecutar-el-registro-de-la-ia-sin-el-modelo.md) y el
[modelo conceptual](../../docs/modelo-conceptual-registro-ia.md). Esta carpeta trae la primera
pieza, el **acta cruda** (#216), y la **curada** que se deriva de ella (#218). El motor
en Docker, el trailer `Registro-IA:` y el visor llegan en issues aparte.

## Cómo funciona

1. **Captura.** `capturar.mjs` corre como hook en `SessionStart`, `PreToolUse`, `PostToolUse`,
   `PostToolUseFailure` y `SessionEnd`; está registrado en `.claude/settings.json`. Agrega una
   línea a `.ia/captura/<sesión>.jsonl` con el `HEAD` y el hash del árbol antes y después de cada
   acción. Las lecturas no capturan árbol, porque no lo cambian. Nunca bloquea la sesión.
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

`.ia/` está en `.gitignore`: las actas pueden traer contenido leído durante la sesión, y qué se
versiona lo decide el issue del trailer.

## A mano

```text
node scripts/acta/compilar-acta.mjs --transcript <sesión.jsonl> [--salida <carpeta>]
node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> [--salida <acta.curada.jsonl>]
node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> --comprobar <acta.curada.jsonl>
node scripts/acta/validar-acta.mjs <acta.cruda.jsonl | acta.curada.jsonl>
node --test scripts/acta/pruebas/compilar-acta.test.mjs scripts/acta/pruebas/capturar.test.mjs
node --test scripts/acta/pruebas/curar-acta.test.mjs scripts/acta/pruebas/verificar-arbol.test.mjs
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

Los transcripts de Claude Code están en `~/.claude/projects/<proyecto>/<sesión>.jsonl`, y los de
sus subagentes en `<sesión>/subagents/`. Los comandos son los mismos en PowerShell y en bash.

## Lo que el acta cruda todavía no tiene

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
- **Huella sin captura.** Si la sesión no tiene captura (por ejemplo, una sesión anterior a este
  hook), no hay `HEAD` base, y el acta sale sin versión del plugin ni documentos de la huella.
