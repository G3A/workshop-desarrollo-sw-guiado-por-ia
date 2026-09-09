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
- **La reformulación se elige, no se impone**: cuando la búsqueda con la pregunta tal cual no
  alcanza y el `Reformulador` (que ve como pistas los fragmentos que esa primera búsqueda encontró,
  para hablar el vocabulario de la fuente) propone dos o más consultas, la página no deja que reescriba en
  silencio — muestra las alternativas (más "usar mi pregunta tal cual"), un campo de texto
  editable precargado con la elegida (lo que se busca es el campo, para corregir a mano cuando
  ninguna sirve) y un checkbox para pedir la respuesta en el idioma original de las fuentes en vez
  de español; solo responde cuando la persona envía su elección. Con una sola alternativa no hay nada que elegir y responde de inmediato. Son
  dos llamadas a `/api/chat` sobre el mismo turno: la
  primera con `proponer=true` termina en el evento SSE `reformulaciones`; la segunda lleva
  `busqueda` e `idioma` y ya no reformula. Un F5 con el panel abierto pierde la elección, igual que
  pierde una respuesta a medio generar.
- **Las acciones son un menú sobre la selección que ya existe, y cada resultado es un turno más**:
  el control «Acciones sobre N documentos» vive pegado a la lista de casillas de la barra lateral
  (la misma selección por conversación que acota las preguntas, F11) y despliega cinco acciones,
  el idioma del resultado y, para traducir, origen y destino. No hay un modo ni una pantalla
  aparte: resumen, síntesis, preguntas, ideas y traducción se pintan como turnos con su etiqueta,
  se guardan en el historial y no llevan feedback 👍/👎 ni «Resultados rápidos», porque no son
  respuestas del RAG. El bloque «Documentos usados» muestra por documento cómo entró al modelo:
  entero de una vez, o «leído completo en N pasadas» con el progreso pasada a pasada mientras
  corre; lo inexistente se marca «no indexado», la misma regla de nunca ocultar la falta de
  evidencia. Preguntas e ideas se pintan desde datos (salida
  estructurada), así el botón «Preguntar» y las tarjetas salen exactas; «Preguntar» solo rellena
  la barra de entrada. La lluvia de preguntas se organiza por los seis niveles de la taxonomía de
  Bloom (recordar, comprender, aplicar, analizar, evaluar, crear), una sección por nivel, con la
  plantilla 5W1H por pregunta (chip «Qué», «Quién», «Cuándo», «Dónde», «Por qué», «Cómo»); los
  niveles llegan uno a uno y la sección se va completando, con «Sin preguntas de este nivel» en
  los que el documento no da.
- **El traductor está donde está el texto**: para escribir en otro idioma, un modo traducir en la
  barra de entrada con una barra visible que avisa que lo enviado se traduce y no se busca; para
  leer en otro idioma, «Traducir» sobre cualquier burbuja o respuesta, con la traducción plegable
  debajo del original y sus citas intactas. Un solo selector de idiomas (`idiomas.js`) para las
  tres entradas, con «Detectar automáticamente» como origen. Mockups que fijaron estas decisiones:
  [menú de acciones](https://claude.ai/code/artifact/5f8cd93e-e2e6-4b0f-b7f4-dd422d54ba0c) y
  [traductor en el chat](https://claude.ai/code/artifact/77d33547-b0f9-406d-b687-8eee184cf128).
- <!-- TODO: agregar más principios si el equipo los formaliza; hoy solo estos cuatro son
  verificables desde el código y la arquitectura documentada. -->

## Docs relacionados

- [Usuario objetivo](./target-user.md)
- [Arquitectura](./architecture.md)
