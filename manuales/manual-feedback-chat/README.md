# Manual completo — de Fundamentos a Sprint cerrado, sobre un caso real de negocio

Recorre el [proceso operacional con IA](../../proceso-operacional-con-ia/) completo — el camino
feliz de los 6 carriles, de `f0` a `end_ok` — sobre `base-conocimiento-sandbox`. A diferencia del
[manual completo existente](../manual-proceso-completo/) (que reutiliza un caso ya cerrado para el
bloque C), acá el bloque C es un ciclo real ejecutado de punta a punta durante la propia
construcción de este manual: issue [#3](https://github.com/G3A/base-conocimiento-sandbox/issues/3),
PR [#4](https://github.com/G3A/base-conocimiento-sandbox/pull/4) y PR
[#5](https://github.com/G3A/base-conocimiento-sandbox/pull/5), todas reales, todas mergeadas.

## Qué hay acá

- `index.html` — el manual completo.

## Lo que este manual no esconde

Dos gaps reales, no maquillados: `/sdlc-ia:debt-triage` nunca se corrió sobre este repo (no hay
nada que triar todavía, pero tampoco se verificó), y no existe ningún workflow de CD/despliegue —
el nodo `cd1`/`G8` no tiene un artefacto real que mostrar, solo el CI del merge a `dev`. Además, el
Ruleset del repo bloqueaba cualquier merge (nadie puede aprobar su propia PR en un repo de una sola
persona) hasta que se le agregó un bypass actor de administrador — ese ajuste de gobernanza queda
documentado como parte del ciclo, no escondido.

## Estilo

Mismo patrón visual y pedagogía "fotograma a fotograma" que el resto de manuales de este monorepo.
