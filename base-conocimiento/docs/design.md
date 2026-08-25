# Diseño — Base de Conocimiento

## Sistema de diseño

<!-- TODO: no hay tokens de diseño, guía tipográfica ni Figma/Storybook en el repo — la UI es
HTML/JS servido como estáticos (`src/main/resources/static/`), sin build ni framework de
componentes. Completar si el equipo formaliza un sistema de diseño más adelante. -->

## Patrones de componentes

La UI vive en `src/main/resources/static/` como archivos planos: `index.html`, `app.js`,
`admin.html`/`admin.js`, `ayuda.js`, `historial-db.js` — sin bundler, sin JSX, sin dependencias de
build. El adaptador `web` (`ChatController`, `WebConfig`, `RedireccionIndiceFilter`) solo expone
REST y Server-Sent Events; toda la lógica de UI es JavaScript plano consumiendo esos endpoints.

## Principios de UX

- **Resultado inmediato, síntesis después**: la búsqueda de texto completo se muestra al instante
  (`Consultar.previsualizar`) mientras el pipeline completo de 7 etapas corre en paralelo y
  transmite la síntesis por SSE (`Consultar.responderEnStreaming`) — el patrón "keyword search on
  landing" del artículo de Cerebras que inspira la arquitectura.
- **Nunca ocultar la falta de evidencia**: si no hay evidencia suficiente, la UI debe mostrar ese
  corte explícito, no una respuesta genérica sin citas.
- <!-- TODO: agregar más principios si el equipo los formaliza; hoy solo estos dos son
  verificables desde el código y la arquitectura documentada. -->

## Docs relacionados

- [Usuario objetivo](./target-user.md)
- [Arquitectura](./architecture.md)
