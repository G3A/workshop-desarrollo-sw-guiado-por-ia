# workshop-desarrollo-sw-guiado-por-ia

Monorepo del taller de desarrollo de software guiado por IA.

## Proyectos

- [`base-conocimiento/`](base-conocimiento/README.md) — RAG interno con citas verificables:
  documentos, código, canales de Teams y work items en una sola tabla de embeddings, 100% local.
- [`proceso-operacional-con-ia/`](proceso-operacional-con-ia/README.md) — visor interactivo del
  ciclo de onboarding al desarrollo operacional guiado por IA, sobre GitHub y GitHub Actions.
- [`instrumentacion-java-ia/`](instrumentacion-java-ia/README.md) — plugin de Claude Code
  (`sdlc-ia`) con 8 skills que instrumentan un repo Java/Spring para desarrollo guiado por IA:
  contexto, controles deterministas, hooks del agente, requisito→spec, el ciclo ticket→PR sobre
  GitHub, triaje de deuda y pruebas sobre código legacy.
- [`playbook-sdlc-ia/`](playbook-sdlc-ia/README.md) — diagrama interactivo de las 7 fases del
  método donde cada caja dice **quién la ejecuta hoy**: una skill del plugin, tú a mano con el
  visor al lado, o nadie todavía. Mapa del método y backlog de cobertura en el mismo archivo;
  funciona offline con doble clic.

## Ramas

### `main` es lo estable; `dev` es donde se integra

- **`dev`** es la rama de integración: toda rama de trabajo (`feat/`, `fix/`, `docs/`) abre su PR
  contra `dev`.
- **`main`** es la rama estable, lo liberado. Solo recibe **PRs de liberación desde `dev`**, cuando
  todo lo que hay en `dev` está en verde y se decide publicar. Nunca una rama de trabajo directo.
- Lo que `main` contenía antes de adoptar este esquema quedó preservado en la rama
  `snapshot/main-antes-del-primer-release`, que no se borra ni se mergea.

El repositorio hermano [`base-conocimiento-sandbox`](https://github.com/G3A/base-conocimiento-sandbox)
sigue otro esquema a propósito: allí `main` es el snapshot «antes de instrumentar con IA» que los
manuales muestran en capturas, y no se mergea.

### Ramas que no se tocan

> **Aviso para cualquier persona o agente que limpie ramas o integre este repositorio.**
>
> Aquí hay ramas que *parecen* atrasadas o abandonadas y son material didáctico. Ninguna tiene
> protección de rama, así que borrarlas o mergearlas sale sin ninguna fricción. Lee esto antes.

### Las ramas `validacion/*` no se borran

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
