# Caso real — TODO de Actuator corregido de punta a punta

Documenta, con capturas de pantalla reales (no mockups), una ejecución real del
[proceso operacional con IA](../../proceso-operacional-con-ia/) sobre `base-conocimiento`: un TODO
desactualizado en `docs/infrastructure.md` corregido vía issue → rama → implementación → gate local
→ PR → CI → merge, terminando en un hallazgo real no guionado (el auto-close de GitHub no dispara
al mergear a una rama que no es el default branch).

Evidencia verificable en GitHub: issue
[#5](https://github.com/G3A/workshop-desarrollo-sw-guiado-por-ia/issues/5), PR
[#6](https://github.com/G3A/workshop-desarrollo-sw-guiado-por-ia/pull/6).

## Cómo verla

Página estática autocontenida, sin `fetch` — las imágenes cargan por ruta relativa, así que
funciona directo con doble clic (`file://`), sin necesidad de servidor HTTP.

## Qué hay acá

- `index.html` — la página, con las 7 capturas reales embebidas.
- `assets/shots/` — las capturas (`.png`) tomadas durante la ejecución real, más
  `capture-clean.ps1`, el script de PowerShell reutilizado para tomarlas: abre la URL en una
  ventana **nueva de Edge en modo InPrivate** (sin las demás pestañas del navegador principal) y
  captura solo el rectángulo de esa ventana — no la pantalla completa — para no filtrar pestañas ni
  ventanas ajenas al caso.

## Estilo

Inspirado en la capacitación interna "Cómo registrar" del CoE de intempo (misma paleta oscura,
mismo patrón de fotograma numerado) — recreado desde cero para este repo público, sin datos ni
identidad de esa organización. A diferencia del original (que usa mockups HTML/CSS de las
pantallas), acá cada `.foto` envuelve una captura de pantalla real.
