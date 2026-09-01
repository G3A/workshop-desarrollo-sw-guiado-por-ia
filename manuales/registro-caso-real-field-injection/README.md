# Caso real — field injection confirmado, ciclo completo por issue

Segunda ejecución real del [proceso operacional con IA](../../proceso-operacional-con-ia/) sobre
`base-conocimiento`, esta vez recorriendo el bloque C completo del diagrama (el ciclo que se
repite issue tras issue) de punta a punta — `bo1` a `c5`, nodo por nodo — sin repetir los dos
tropiezos del [primer caso](../registro-caso-real-actuator/) (reabrir/cerrar un issue real para
retomar una captura, y asumir el auto-close sin verificar el default branch).

Evidencia verificable en GitHub: issue
[#7](https://github.com/G3A/workshop-desarrollo-sw-guiado-por-ia/issues/7), PR
[#8](https://github.com/G3A/workshop-desarrollo-sw-guiado-por-ia/pull/8).

## Cómo verla

Página estática autocontenida, sin `fetch` — las imágenes cargan por ruta relativa, así que
funciona directo con doble clic (`file://`), sin necesidad de servidor HTTP.

## Qué hay acá

- `index.html` — la página, recorriendo cada nodo del bloque C del proceso (planificar, crear
  rama, ejecutar tareas, verificación en 4 capas, PR, CI, merge, retrospectiva...) con la evidencia
  real de este caso puntual, o una nota breve donde un nodo no aplicó.
- `assets/shots/` — las capturas (`.png`) tomadas durante la ejecución real, más:
  - `capture-clean.ps1` — script para páginas de GitHub. Reescrito en esta sesión respecto al
    heredado del caso anterior: la versión original usaba `SetForegroundWindow` +
    `CopyFromScreen`, que puede terminar capturando otra ventana si Windows bloquea el cambio de
    foco entre procesos (pasó una vez armando este caso). La versión actual captura con
    `PrintWindow` (`PW_RENDERFULLCONTENT`) — le pide al propio proceso dueño de la ventana que
    renderice su contenido, sin depender del foco — y verifica por título (proceso `msedge` +
    `[InPrivate]` + fragmento del título esperado) antes de guardar cualquier imagen, reintentando
    si detecta que quedó en blanco.
  - `capture-window.ps1` — misma idea que el anterior, pero para capturar una ventana local ya
    abierta (la terminal de la propia sesión de Claude Code) en vez de lanzar Edge.

## Estilo

Mismo patrón visual que el [caso Actuator](../registro-caso-real-actuator/) — recreado desde cero
para este repo público, sin datos ni identidad de ninguna organización externa.
