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
  de su `SKILL.md`, tal como está escrito en el encabezado.
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

Los hechos se reportan, no se juzgan: volver de la Phase 5 a la 3 es lo que `debt-triage` manda
cuando falla su gate.

**Sin marcadores, la respuesta es «sin datos».** No se listan acciones sin paso ni pasos
omitidos: inferirlos metería una heurística en el registro (PC-04).

## Qué skills lo emiten

| Skill | Fase del método | Pasos |
|---|---|---|
| `debt-triage` | 2 · Preparación del terreno | Phase 1 a 5 |

Las otras ocho se suman de a una. Cada una toca su `SKILL.md` (en inglés), su doc en español, el
nodo del visor que la cita y la caja del playbook que la nombra, en el mismo PR.

## Lo que el protocolo no garantiza

Que el modelo no olvide un marcador. Si lo olvida, esas acciones quedan en un paso `ausente` y la
conformidad las muestra como acciones sin paso. No se corrigen después ni se infiere el paso que
faltó.
