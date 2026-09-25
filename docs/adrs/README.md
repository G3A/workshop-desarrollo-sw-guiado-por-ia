# Registros de Decisiones (ADRs) del monorepo

Decisiones que atraviesan más de una pieza del monorepo: esquema de ramas, liberaciones,
convenciones compartidas. Las decisiones propias de cada pieza viven en su carpeta, por ejemplo
[`base-conocimiento/docs/adrs/`](../../base-conocimiento/docs/adrs/).

Mismo formato que allí: `adr-template.md` como punto de partida, cuatro secciones (Estado,
Contexto, Decisión, Consecuencias), una página, nombre `NNNN-slug-corto.md`.

## Índice

- `0001-liberar-con-chore-release-a-dev` — liberar es una PR `chore/release-<versión>` a `dev`
  seguida de la PR `dev` → `main`, no una rama `release/` al estilo git-flow.
- `0002-equivalencia-entre-el-monorepo-y-el-sandbox` — **reemplazado por el 0003.** Decidió que la
  relación espejo con `base-conocimiento-sandbox` y su lista de divergencias viven en un ADR de
  esta carpeta, en vez de en mensajes de commit o en el `claims-ledger` de cada repo.
- `0004-el-metodo-exige-el-despliegue-y-no-la-herramienta` — el tramo de producción se expresa como
  tres propiedades —lo dispara la liberación, la aprobación queda registrada, hay un camino de
  vuelta ensayado— y ningún comando de proveedor. La reversa enseña el criterio, no el mecanismo.
- `0003-el-adr-como-fuente-de-datos-del-sensor-de-espejo` — la lista de divergencias deja de ser un
  acuerdo escrito: la lee `scripts/verificar-espejo.mjs` en el CI. Qué se espeja, qué difiere a
  propósito, qué es residuo pendiente de limpieza, y qué bloquea el job frente a qué solo avisa.
