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
- `0005-re-ejecutar-el-registro-de-la-ia-sin-el-modelo` — el registro de la IA se re-ejecuta con
  los comandos y resultados grabados del orquestador y sus subagentes, no con el modelo, en un
  contenedor sin red. Acta cruda y curada, cuatro clases de determinismo y anexo de
  verificaciones negativas. Reproduce, no replica.
- `0006-el-acta-de-la-ia-se-versiona-en-git` — el acta va en git, en `.ia/registros/` con
  `git add -f`, junto a un pack de los árboles de la sesión. Todo commit asistido declara
  `Registro-IA:`, y el CI falla si el acta no llega a su diff. Enmienda el 0005: un comando
  ejecutado que diverge en el contenedor se avisa, no falla.
- `0007-el-acta-viaja-con-el-plugin` — el acta se muda al plugin `sdlc-ia` y sus hooks la
  registran en el repo donde se usa, no solo en este monorepo. Una sola copia; aquí la captura del
  árbol de trabajo tiene precedencia sobre la del plugin, y la huella guarda los instructivos del
  plugin instalado como blobs.
- `0003-el-adr-como-fuente-de-datos-del-sensor-de-espejo` — la lista de divergencias deja de ser un
  acuerdo escrito: la lee `scripts/verificar-espejo.mjs` en el CI. Qué se espeja, qué difiere a
  propósito, qué es residuo pendiente de limpieza, y qué bloquea el job frente a qué solo avisa.
