# Acta de la IA

El registro de lo que hizo la IA en una sesión de Claude Code, según el
[ADR-0005](../../docs/adrs/0005-re-ejecutar-el-registro-de-la-ia-sin-el-modelo.md) y el
[modelo conceptual](../../docs/modelo-conceptual-registro-ia.md). Esta carpeta trae la primera
pieza, el **acta cruda** (#216), y la **curada** que se deriva de ella (#218, en curso). El motor
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

`.ia/` está en `.gitignore`: las actas pueden traer contenido leído durante la sesión, y qué se
versiona lo decide el issue del trailer.

## A mano

```text
node scripts/acta/compilar-acta.mjs --transcript <sesión.jsonl> [--salida <carpeta>]
node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> [--salida <acta.curada.jsonl>]
node scripts/acta/validar-acta.mjs <acta.cruda.jsonl | acta.curada.jsonl>
node --test scripts/acta/pruebas/compilar-acta.test.mjs scripts/acta/pruebas/capturar.test.mjs
node --test scripts/acta/pruebas/curar-acta.test.mjs
```

La curada entra solo con las acciones exitosas, de todos los agentes, con su id de la cruda; los
pasos y turnos que quedan vacíos salen. Por ahora se corre a mano: el hook de cierre de sesión la
va a generar cuando la curación esté completa.

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
- **Huella sin captura.** Si la sesión no tiene captura (por ejemplo, una sesión anterior a este
  hook), no hay `HEAD` base, y el acta sale sin versión del plugin ni documentos de la huella.
