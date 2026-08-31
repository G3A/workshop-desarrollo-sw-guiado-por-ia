# workshop-desarrollo-sw-guiado-por-ia

Monorepo del taller de desarrollo de software guiado por IA.

## Proyectos

- [`base-conocimiento/`](base-conocimiento/README.md) — RAG interno con citas verificables:
  documentos, código, canales de Teams y work items en una sola tabla de embeddings, 100% local.
- [`proceso-operacional-con-ia/`](proceso-operacional-con-ia/README.md) — visor interactivo del
  ciclo de onboarding al desarrollo operacional guiado por IA, sobre GitHub y GitHub Actions.
- [`instrumentacion-java-ia/`](instrumentacion-java-ia/README.md) — plugin de Claude Code
  (`sdlc-ia`) con 4 skills que instrumentan un repo Java/Spring para desarrollo guiado por IA:
  contexto, controles deterministas, hooks del agente, y el ciclo ticket→PR sobre GitHub.

## Las ramas `validacion/*` no se borran

> **Aviso para cualquier persona o agente que limpie ramas en este repositorio.**

Las ramas `validacion/f0-fundamentos`, `f1-preparar-maquina`, `f2-preparar-proyecto`,
`f3-planificar`, `f6-merge-cd` y `f7-retrospectiva` **son material didáctico**: muestran en acción,
fase por fase, cómo se usa el método Arkandia. Su valor es el historial visible, no el código que
aportan — se leen junto a [`validacion-workshop/`](validacion-workshop/).

Por eso **no se borran nunca**, y en particular:

- **Tampoco las que ya están integradas en `dev`.** `f6-merge-cd` y `f7-retrospectiva` no tienen
  ningún commit fuera de `dev`, así que cualquier limpieza automática de "ramas ya mergeadas" las
  marcaría como descartables. No lo son.
- **Tampoco las que parecen abandonadas.** `f0`–`f3` sí tienen commits propios sin integrar; eso es
  parte de lo que enseñan.

Al limpiar ramas, borra solo las de trabajo con su PR mergeado y deja `validacion/*` fuera del
alcance.
