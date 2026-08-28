# requirement-to-spec-java

## Qué es

Convierte un documento de requisitos de negocio (Word, PDF, Excel, Markdown, texto plano) en una
especificación y un desglose de tareas, para un repositorio Java/Spring. Detecta qué controladores
REST, entidades JPA y migraciones toca el requisito; señala qué documentación existente el cambio
dejaría desactualizada, citando la línea exacta; solo pregunta lo que el documento y el repositorio
no pudieron responder entre los dos.

Corre **antes** de tomar un issue — cierra el hueco que la actividad 17 del manual
`manual-feedback-chat` deja explícito: hoy, quien escribe el issue lo hace explorando el repo a
mano ("ejercicio de actora de requisitos"). Esta skill es la versión reproducible de esa
exploración.

**Nunca escribe código, nunca abre PR, nunca toca git.** Ese límite es lo que la hace segura de
correr temprano, sobre un documento que todavía nadie aprobó — el mismo límite que
`github-plan-build`, la otra skill de entrega del paquete, deliberadamente no comparte (empuja
ramas y abre PRs, por diseño, una vez que un plan quedó aprobado).

## Cómo se invoca

```
/sdlc-ia:requirement-to-spec-java <ruta al documento de requisitos>
```

## Las seis fases

1. **Descubrimiento silencioso** — convierte el documento a Markdown si hace falta (vía `pandoc`,
   ya instalado o no — nunca lo instala esta skill), lee el contexto
   del repo (`AGENTS.md`, `docs/architecture.md`, ADRs), detecta contratos públicos que el
   requisito toca (controladores `@RestController`, entidades `@Entity` y su migración,
   OpenAPI/`.proto` si existen), detecta qué documentación el cambio dejaría desactualizada
   (cita archivo y línea, nunca "puede que algún doc necesite actualizarse"), detecta trackers
   disponibles (GitHub, vía la misma señal que usa `github-plan-build`) y si hay un servidor MCP
   de base de datos registrado.
2. **Prerrequisitos** — reporta qué encontraron los chequeos de la Fase 1 (`pandoc --version` o su
   ausencia, `gh auth status`); no instala nada por su cuenta.
3. **Acordar el alcance** — máximo 4 preguntas por llamada, sin jerga. Seis categorías siempre
   activas: impacto en contrato público, cruce tabular contra la base de datos (solo si hay
   servidor MCP y datos tabulares adjuntos), documentación desactualizada (se presenta la lista
   citada y se pregunta qué actualizar ahora), attachments faltantes (se reportan, no se
   preguntan), barrido de ambigüedad (actores, disparadores, forma de los datos, casos borde —
   registrado como Decisión o Supuesto, mismo vocabulario que el Step A de `github-plan-build`), y
   criterios de validación en forma doble (qué prueba que se cumplió, qué probaría que no).
   Siempre pregunta el destino (trackers detectados + "archivo local"), nunca lo asume.
4. **Aplicar** — según el destino elegido: en modo archivo, `docs/specs/<slug>/spec.md` +
   `docs/specs/<slug>/tasks.md`; en modo tracker, un issue de GitHub con un sub-issue nativo por
   tarea (`gh issue create --parent <n>`, reutiliza el mismo `gh` que ya usa `github-plan-build`,
   no inventa un segundo camino).
5. **Verificar** — modo archivo: ambos archivos existen y sus referencias cruzadas resuelven; modo
   tracker: se vuelve a consultar (`gh issue view --json subIssues,subIssuesSummary,parent`) para
   confirmar que la relación padre-hijo quedó de verdad, no solo que el comando no falló.
6. **Reportar** — cuatro secciones siempre presentes (vacías si no aplica): Preguntado,
   Respondido (Decisiones y Supuestos), Fuera de alcance, No leído (attachments que no se pudieron
   abrir). Cierra con un paso siguiente concreto: `/sdlc-ia:github-plan-build <n>` en modo tracker,
   o la ruta a `spec.md` en modo archivo.

## Qué archivos toca o crea

- Modo archivo: `docs/specs/<slug>/spec.md` y `docs/specs/<slug>/tasks.md` (bilingües, mismo
  patrón que `agent-context-java`: español por defecto, inglés si `AGENTS.md`/`docs/` ya están en
  inglés).
- Modo tracker: ningún archivo local — un issue de GitHub y sus sub-issues, vía `gh issue create`.

**Nunca hace `commit` ni `push`.** `gh issue create` habla con la API de GitHub, no es una
escritura de git — el límite de esta skill contra tocar git se sostiene igual.

## Decisiones de diseño a tener en cuenta

- **Nunca inventa contenido.** Un attachment que no se pudo leer se reporta como no leído — nunca
  se describe "probablemente dice X".
- **Cita, no categoriza.** Cuando un cambio deja una documentación desactualizada, se nombra el
  archivo y la línea — "puede que algunos docs necesiten actualizarse" no es un hallazgo.
- **Nunca decide por el usuario**: si un contrato público puede romperse, si una documentación
  desactualizada se actualiza ahora o después, qué queda fuera de alcance, si un criterio de
  validación bloquea el release. Cada uno se presenta con su evidencia; la decisión es del
  usuario.
- **Trabajo funcional primero, documentación al final.** Una lista de tareas que pone "actualizar
  el README" antes de la funcionalidad que documenta tiene el orden invertido.
- **Cada tarea tiene que ser del tamaño de un commit** de la Fase C de `github-plan-build` — si es
  más grande, se desglosa acá, no se deja para después.
