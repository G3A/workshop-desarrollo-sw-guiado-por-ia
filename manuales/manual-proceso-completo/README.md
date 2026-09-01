# Manual completo — de Fundamentos a Sprint cerrado

Recorre el [proceso operacional con IA](../../proceso-operacional-con-ia/) completo — los 6 carriles,
~30 nodos distintos — sobre `base-conocimiento`, sin asumir que ningún nodo ya está resuelto. Para
cada uno se muestra evidencia real (comando, commit, o captura) de su estado actual: hecho,
pendiente, o no aplica, con el motivo.

El bloque C (el ciclo por issue) reutiliza el [caso real ya documentado](../registro-caso-real-field-injection/)
en vez de tocar GitHub de nuevo sin necesidad — issue #7, PR #8.

## Cómo verla

Página estática autocontenida, sin `fetch` — las imágenes del bloque C cargan por ruta relativa
desde `../registro-caso-real-field-injection/assets/shots/`, así que funciona directo con doble
clic (`file://`), siempre que esa carpeta hermana siga existiendo en el mismo repo.

## Qué hay acá

- `index.html` — el manual completo.

## Lo que este manual no esconde

Tres brechas reales quedaron identificadas, no maquilladas: dos servidores MCP registrados sin sus
variables de entorno (`GITHUB_PAT`, `APP_DSN`), un Ruleset de GitHub sin configurar sobre `dev`, y
35 violaciones de Checkstyle de brownfield sin una pasada de `/sdlc-ia:debt-triage`. Ninguna
bloquea el ciclo por issue, pero son la lista concreta de qué atender antes de la próxima vuelta.

## Estilo

Mismo patrón visual y pedagogía "fotograma a fotograma" que los casos reales de este monorepo —
recreado desde cero, sin datos ni identidad de ninguna organización externa.
