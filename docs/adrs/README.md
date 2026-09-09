# Registros de Decisiones (ADRs) del monorepo

Decisiones que atraviesan más de una pieza del monorepo: esquema de ramas, liberaciones,
convenciones compartidas. Las decisiones propias de cada pieza viven en su carpeta, por ejemplo
[`base-conocimiento/docs/adrs/`](../../base-conocimiento/docs/adrs/).

Mismo formato que allí: `adr-template.md` como punto de partida, cuatro secciones (Estado,
Contexto, Decisión, Consecuencias), una página, nombre `NNNN-slug-corto.md`.

## Índice

- `0001-liberar-con-chore-release-a-dev` — liberar es una PR `chore/release-<versión>` a `dev`
  seguida de la PR `dev` → `main`, no una rama `release/` al estilo git-flow.
