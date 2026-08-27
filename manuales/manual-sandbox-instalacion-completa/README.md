# Manual sandbox — instalación desde cero

Tercera vuelta de esta serie: instalación genuinamente desde cero del plugin `sdlc-ia`
(desactivado a propósito antes de empezar) sobre un repositorio real y desechable,
[`base-conocimiento-sandbox`](https://github.com/G3A/base-conocimiento-sandbox). El ciclo por
issue lo dispara **Camila Reyes, Ingeniera de Requisitos** — la única persona ficticia de esta
página; el resto es real, sin actuar.

Evidencia verificable en GitHub: issue
[#1](https://github.com/G3A/base-conocimiento-sandbox/issues/1), PR
[#2](https://github.com/G3A/base-conocimiento-sandbox/pull/2), el
[Ruleset](https://github.com/G3A/base-conocimiento-sandbox/rules/21661965) real sobre `dev`.

## Cómo verla

Página estática autocontenida, sin `fetch` — funciona directo con doble clic (`file://`).

## Qué hay acá

- `index.html` — el manual. Incluye las tres sesiones completas de Claude Code
  (`instrument-agent-java`, `agent-context-java`, `instrument-project-java`) embebidas verbatim
  en bloques con scroll propio, no recortadas.
- `assets/shots/` — capturas reales del issue, PR, CI y Ruleset.
- `assets/transcripts/` — el texto crudo de las tres sesiones, tal como las pegó el usuario,
  como `.txt` aparte (la misma fuente que `index.html` embebe ya escapada).

## Por qué un repo aparte

`sdlc-ia` ya estaba instalado a nivel de **usuario** en la máquina donde se hizo este taller —
no había forma de demostrar una instalación limpia sin desactivarlo, y desactivarlo sobre el
repo real del workshop (con instrumentación ya hecha en los dos casos anteriores) hubiera sido
confuso. Un repo desechable, sembrado desde un snapshot del código real anterior a toda
instrumentación de IA, permite mostrar el proceso completo sin ese riesgo.

## Estilo

Mismo patrón visual y pedagogía "fotograma a fotograma" que los casos reales de este monorepo,
con un agregado puntual: bloques de sesión completa con scroll vertical propio, para no recortar
las transcripciones reales.
