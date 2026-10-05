# Protocolo de marcadores de paso

Cómo una skill de `sdlc-ia` le dice al registro de la IA en qué paso de su instructivo está y a qué
fase del método pertenece (#222, fase 4). Responde la fase de PC-01 y PC-02, PC-04 con procedencia
`marcado`, PC-05 y PC-06 del [modelo conceptual](modelo-conceptual-registro-ia.md).

## El marcador

Una línea sola en el texto de la skill, con este formato exacto:

```text
[sdlc-ia:step skill=debt-triage step=2 method-phase=2]
```

- `skill`: el nombre de la carpeta de la skill.
- `step`: el número de un encabezado `Phase N` o la letra, una sola mayúscula, de un `Step X`
  de su `SKILL.md`, tal como está escrito en el encabezado. Si el `SKILL.md` no tiene esos
  encabezados, el de una fila de su tabla de fases, cuya primera columna es `Phase` o `Step`
  (`| 1 — Discover (silent) | …`).
- `method-phase`: la fase del playbook que la skill declara, de 0 a 6. Es la misma en todos los
  marcadores de la skill; no se deduce del paso.

Se admite entre backticks, porque el modelo suele escribirlo así. Cualquier otra variante no es un
marcador: el compilador no adivina.

## Cuándo se emite

1. **Al empezar cada paso**, antes de su primera herramienta.
2. **Al retomar después de una respuesta de la persona**, se repite el del paso en curso. Cada
   prompt abre un turno, y un paso pertenece a un solo turno (invariante 1): sin repetirlo, lo que
   la skill hace después de la respuesta queda sin paso.
3. **Los subagentes no marcan.** Sus acciones van al paso de la llamada que los lanzó
   (invariante 3).

## Por qué texto y no una herramienta

Un comando que escribiera el marcador sería una acción más en el acta, con su árbol y su
veredicto, y dependería del shell. Una línea de texto no agrega ninguna acción y se lee igual en
PowerShell y en bash. El compilador ya lee el texto del asistente para las decisiones (PC-07), y
quita el marcador de ese texto.

## Qué hace el compilador

`scripts/acta/compilar-acta.mjs` abre un paso `marcado` por cada marcador, con
`pasoPrescrito: { skill, letra }` y `faseDeclarada`. Lo que el turno hizo antes del primer marcador
queda en un paso `ausente`, el primero del turno; el mismo paso marcado dos veces seguidas en un
turno es uno solo. El acta lleva la fase cuando todos sus pasos declaran la misma; si declaran
varias, queda en `null` y se avisa. El índice de la tarea lista en `fases` las de todas sus actas.

## Qué responde la conformidad

`node scripts/acta/conformidad.mjs <acta>` compara la secuencia marcada con los encabezados
`Phase N` y `Step X` del `SKILL.md`, leídos del blob que la huella registró y no del archivo de
hoy:

- **Omitidos**: pasos prescritos que no se marcaron.
- **Repetidos**: volver a un paso después de pasar por otro. Retomar el mismo paso no es repetirlo.
- **Fuera de orden**: un paso que llega después de otro posterior en el instructivo.
- **No prescritos**: un `step` que el instructivo no tiene.
- **Acciones sin paso**: las de los pasos `ausente` (PC-06).
- **Iteraciones**: las vueltas por un bucle que el instructivo declara (ver abajo).

Los hechos se reportan, no se juzgan: volver de la Phase 5 a la 3 es lo que `debt-triage` manda
cuando falla su gate.

**Sin marcadores, la respuesta es «sin datos».** No se listan acciones sin paso ni pasos
omitidos: inferirlos metería una heurística en el registro (PC-04).

## Qué skills lo emiten

| Skill | Fase del método | Pasos |
|---|---|---|
| `agent-context-java` | 2 · Preparación del terreno | Phase 1 a 6 |
| `debt-triage` | 2 · Preparación del terreno | Phase 1 a 5 |
| `instrument-agent-java` | 2 · Preparación del terreno | Phase 1 a 6 |
| `instrument-github-repo` | 2 · Preparación del terreno | Phase 1 a 6 |
| `instrument-project-java` | 2 · Preparación del terreno | Phase 1 a 5 |
| `legacy-test-harness` | 2 · Preparación del terreno | Phase 1 a 8 |
| `github-plan-build` | 3 · Ciclo por feature | Phase 0 a 4 |
| `requirement-to-spec-java` | 3 · Ciclo por feature | Phase 1 a 6, en la tabla de fases |
| `impact-metrics` | 5 · Métricas y reporte | Phase 1 a 6 |

Las nueve marcan sus pasos desde #230. La fase del método de cada una es la de su actividad en el
catálogo de `scripts/acta/proceso.json`, y una prueba de `conformidad.test.mjs` exige que el
marcador de cada `SKILL.md` la declare y que `proceso.json` traduzca exactamente sus pasos.

## Pasos en los `references/`

Un `SKILL.md` puede delegar pasos en sus `references/`: los `Step A` a `K` de
`github-plan-build` están en `build-loop.md` y `build-loop-execute.md` (#230). La huella registra
siempre los `references/` de cada skill invocada, en la versión del HEAD base, aunque la sesión no
los lea, y la conformidad los toma de ahí con estas reglas:

1. **Solo cuentan los `Step <LETRA>`.** Un `Phase N` en un reference repite el mapa del
   `SKILL.md` (lo hacen `legacy-test-harness` y `requirement-to-spec-java`), y un `Step 1` choca
   con la Phase 1 (`claim-validation.md` de `agent-context-java`). Ninguno de los dos es un paso
   aparte.
2. **Van anidados bajo la Phase cuya sección nombra el archivo**, justo después de ella y en el
   orden en que la sección lo nombra. En `github-plan-build` queda 0, 1, 2, 3, 4, A, …, K.
3. **Si el acta no trae un reference que la sección nombra**, se dice en `referenciasFaltantes`
   y sus pasos no se inventan.

## Bucles declarados

Un regreso que el instructivo manda no es un error. El `SKILL.md` lo declara en una línea:

```text
- **Loop:** from Step I back to Step G — a red CI sends you to fix it, re-run the gates…
```

Volver de un paso entre `G` e `I` a `G` es una vuelta del bucle: se cuenta en `iteraciones`, y
pasar otra vez por G, H e I durante esa vuelta no es repetirlos ni salir de orden. Un regreso que el
instructivo no declara sigue siendo «repetido» y «fuera de orden»: el registro no supone que fue a
propósito. Hoy solo `github-plan-build` declara un bucle; el regreso de la Phase 5 a la 3 de
`debt-triage` se reporta como hecho, sin declarar.

## El plan de un issue, sin skill

Una sesión que implementa el plan de un issue sin invocar una skill también declara su actividad
(#230). La regla está en `AGENTS.md`: al empezar cada fase del plan, el agente escribe
`[sdlc-ia:step skill=plan-de-issue step=<N> method-phase=3]`. El compilador lo acepta sin skill
invocada solo si el turno es de una tarea con número (la rama `feat/<N>-...`).

| Nivel | Qué es en una sesión sin skill |
|---|---|
| Actividad | «Implementar el plan de un issue», del catálogo de `scripts/acta/proceso.json` |
| Procedimiento | `AGENTS.md` (y `CLAUDE.md`), en la versión que la huella registra siempre |
| Instructivo | el plan del issue, que `registrar-sesion.mjs` guarda en `plan-del-issue.json` con su fecha y su sha256 |

Los pasos prescritos son los encabezados numerados del plan (`## Fase 2 · …`, `## 5 · …`). Sin
`plan-del-issue.json`, la conformidad dice que no tiene contra qué comparar.

Un marcador dentro de un bloque de código (```` ``` ````) es un ejemplo y no cuenta, ni en la sesión
ni en el plan. Cada una toca su `SKILL.md` (en inglés), su doc en español, el
nodo del visor que la cita y la caja del playbook que la nombra, en el mismo PR.

## Lo que el protocolo no garantiza

Que el modelo no olvide un marcador. Si lo olvida, esas acciones quedan en un paso `ausente` y la
conformidad las muestra como acciones sin paso. No se corrigen después ni se infiere el paso que
faltó.
