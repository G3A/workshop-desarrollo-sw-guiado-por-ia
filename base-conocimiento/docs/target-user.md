# Usuario Objetivo — Base de Conocimiento

<!-- Dos adaptadores (web, Teams) sugieren dos modos de acceso al mismo usuario más que dos
personas distintas — no hay evidencia en el repo de perfiles de usuario diferenciados más allá de
esto. Ampliar con datos reales si aparecen (encuestas, tickets, feedback del equipo). -->

## Personas

### Persona 1 — Miembro del equipo, consulta puntual

- **Rol:** desarrollador/a o integrante del equipo que necesita una respuesta rápida sobre código,
  una decisión pasada, un hilo de Teams o un work item.
- **Objetivo:** obtener una respuesta con citas verificables sin tener que buscar manualmente en
  4 sistemas distintos (repos, documentos, Teams, Azure DevOps).
- **Dolor:** las respuestas de un LLM genérico sin fuente son inútiles cuando hay que confiar en
  ellas para decidir algo — necesita poder verificar de dónde salió cada afirmación.

### Persona 2 — Igual, pero desde Teams

- **Rol:** el mismo tipo de usuario que la Persona 1, accediendo desde el canal de Teams en vez de
  la UI web (ej. durante una conversación, sin cambiar de contexto).
- **Objetivo:** el mismo — respuesta con citas, ahora como Adaptive Card dentro del chat.
- **Dolor:** el mismo, más la fricción de tener que salir del chat para buscar en otro lado si el
  bot no da una respuesta confiable.

## Rasgos comunes

| Rasgo | ¿Todas las personas? |
|---|---|
| Necesitan verificar la fuente de cada respuesta, no solo el texto generado | Sí |
| Prefieren una respuesta parcial con "no sé" explícito antes que una alucinada | Sí |
| Acotan sus preguntas a un proyecto/contexto conocido (`ProyectoId`) | Sí |

## Implicaciones para el desarrollo

| Rasgo | Qué significa para el código |
|---|---|
| Necesitan verificar la fuente | Toda respuesta debe llevar citas trazables a `chunks`/`documents` — nunca sintetizar sin evidencia por debajo del umbral de relevancia (ver [ADR-0008](business.md)). |
| Prefieren "no sé" a alucinar | `VerificadorGrounding` y el corte explícito sin síntesis cuando no hay evidencia suficiente no son detalles opcionales — son el contrato con el usuario. |
| Acotan por proyecto | `ProyectoId` debe filtrar el corpus **antes** de que el planner elija herramientas, nunca después. |

## Docs relacionados

- [Negocio](./business.md)
- [Diseño](./design.md)
