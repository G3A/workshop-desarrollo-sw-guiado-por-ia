# Plan — issue #54: el chat muestra el código con resaltado, números de línea y copiar sin los números

## Contexto

Hoy toda respuesta se pinta con `turno.respuesta.textContent += fragmento` (`app.js`), así que un
bloque con ` ``` ` llega con sus marcas, sin forma de copiar solo el código. La base de
conocimiento ingiere repositorios: las respuestas con código son un caso normal. Rama
`feat/54-codigo-en-el-chat`, base `dev` (rama de integración documentada en `AGENTS.md`).

## Decisiones (del usuario)

- **Interfaz B** (comentario del issue): «Fondo del bloque según el tema (gris claro / gris
  oscuro), borde `--borde`, radio de 10 px. Arriba a la derecha: un rótulo con el lenguaje y un
  botón de solo icono de 32×32 con `aria-label="Copiar código"`. Al copiar muestra «Copiado»
  durante unos 2 s. Números de línea en una columna con `user-select: none`; el portapapeles recibe
  solo el código. Mientras llega por streaming, el bloque crece sin botón de copiar hasta que se
  cierra. Fuera de alcance: plegado de bloques largos y «Ajustar líneas» (alternativa C).» Y «usa
  dos paletas de resaltado, una por tema».
- **Librería:** «highlight.js vendorizado» → `@highlightjs/cdn-assets@11.12.0` (`latest` en npm),
  build común + `github.min.css` / `github-dark.min.css` + `LICENSE` (BSD-3).
- **Pruebas:** «node:test sobre el parser»: módulo puro probado con `node --test`, sumado a
  `make check` / `make ci`, más verificación manual con Playwright.
- **Alcance:** «Traducir» (bloques `.texto-traducido`) fuera; sigue en texto.

## Supuestos (míos, a revisar en este checkpoint)

1. Solo cercas de backticks (≥3, hasta 3 espacios de sangría, que se quitan de cada línea del
   contenido como en CommonMark). `~~~`, código en línea y el resto de Markdown siguen como texto.
2. `\r\n` se normaliza a `\n` antes de partir (la copia sale con `\n`).
3. Lenguaje = primera palabra del info string, `trim` y minúsculas, lista blanca `[\w+#.-]`; se
   asigna con `dataset`/`setAttribute`, nunca concatenando HTML.
4. Una línea de cierre solo cierra si va seguida de `\n` **o** el turno terminó (evita el parpadeo
   de ` ``` ` → ` ```js ` a mitad del stream). Una apertura sin `\n` todavía abre el bloque sin
   lenguaje.
5. Lenguaje declarado desconocido para highlight.js → sin resaltado (`plaintext`), rótulo con lo
   declarado. Sin lenguaje → `highlightAuto` **solo** con el bloque cerrado o el turno terminado; si
   no detecta nada, rótulo «texto».
6. Bloque nunca cerrado con el turno ya terminado (fin, error, resuelto, historial) → se trata como
   cerrado (copiar visible). `respuestaTerminada` se fija en esos caminos, **no** en
   `cerrarStreaming` cuando `turno.eligiendo` (el primer stream de propuestas).
7. Botón de copiar siempre visible (el mockup B lo dibuja así; sirve en táctil).
8. Colores del resaltado = las dos hojas github vendorizadas (la decisión pide dos paletas, una por
   tema); el bloque en sí usa tokens: nuevo `--bg-codigo` (claro `#f6f8fa`, oscuro
   `var(--bg-elevado)` = `#2a2a2a`), `--borde`, `--texto-atenuado`, `--acento`.
9. `innerText` de `.turno-respuesta` (lo lee `eval-100-preguntas/ejecutar.js:106`) pasa a dar el
   código **sin** las cercas: números, rótulo, icono y «Copiado» van por CSS (`::before`/`::after`,
   SVG con `aria-hidden`), así que no se cuelan. Se avisa en la PR.

## Enfoque técnico (resultado de la revisión adversarial)

- **Fuente de verdad = string**: `turno.respuestaTexto` (inicializado en `""` en `nuevoTurno`).
  Todos los escritores (`app.js` ~502, 567, 1170, 1418, 1549, 1911, confirmados con grep antes de
  empezar) asignan/concatenan ahí y llaman `pintarRespuesta(turno)`; los lectores (`traducirTurno`
  ~2036, `guardarTurno` ~2243) leen el string. IndexedDB ya guardaba el texto crudo: **sin
  migración**.
- **DOM del bloque**: `<div class="bloque-codigo" data-lenguaje>` con `.acciones-codigo` (rótulo por
  `::before attr()`, botón SVG 32×32) y `<pre><code>` con `white-space: pre`, cada línea un
  `<span class="linea">` **inline** separado por nodos `"\n"`; el número sale de
  `counter-increment` en `.linea::before` (`user-select:none`). Así `textContent` e `innerText`
  dan el código exacto, líneas vacías incluidas.
- **Copiar** lee el string `codigo` del segmento (guardado en un `WeakMap` bloque → código), nunca
  el DOM; mismo patrón que `.boton-copiar` (`app.js` ~840: `try/catch` con el porqué, botón
  `disabled` durante la confirmación), dentro del mismo listener delegado de `historial`, clase
  propia `.boton-copiar-codigo`; «Copiado» ~2 s con clase `.copiado`.
- **Repintado incremental**: se re-parte el texto y se reemplazan los nodos desde el primer
  segmento distinto (`primerSegmentoDistinto`, función pura probada); el bloque abierto conserva su
  contenedor (solo cambia el `innerHTML` de su `<code>`), así no se pierde el `scrollLeft`.
- **`innerHTML` solo con HTML de highlight.js** (que escapa; para lo no resaltado,
  `hljs.highlight(c, {language:"plaintext"})`), nunca con texto del modelo. `hljs.highlight` con
  guardia `hljs.getLanguage()`. **Un solo escapador**: `codigo.js` no trae otro `escaparHtml`.

## Pasos

1. **Prueba que falla** — `src/test/js/codigo.test.js` (node:test, CommonJS) contra
   `static/codigo.js` (inexistente → rojo). Casos: sin cercas; con y sin lenguaje; varios bloques;
   sin cerrar; apertura sin `\n`; cierre sin `\n` (no cierra) y con `terminado` (cierra); cerca de 4
   que contiene ```; sangría; CRLF; info string `java title=x` y `"><img>` (rechazado); el `\n`
   que rodea las cercas no queda en el texto; `dividirEnLineas` con comentario multilínea, string
   con entidades y spans anidados (cada línea balanceada; el texto decodificado == código);
   `primerSegmentoDistinto`; un test que falla si `index.html` carga algo por `http(s)://`.
   Verificación: `node --test "src/test/js/*.test.js"` rojo.
2. **`static/codigo.js`** — IIFE `"use strict"` como `idiomas.js`/`historial-db.js`:
   `partir(texto, terminado)`, `dividirEnLineas(html)`, `primerSegmentoDistinto(a, b)`; expone
   `window.kbCodigo` y, solo si existe `module`, `module.exports`. Verificación: verde.
3. **Vendor** — `npm pack @highlightjs/cdn-assets@11.12.0` en el scratchpad; copiar
   `highlight.min.js`, `styles/github.min.css`, `styles/github-dark.min.css`, `LICENSE` a
   `static/vendor/highlight/`. Comprobar que `plaintext` existe en la build. `make secrets`; un
   falso positivo se silencia por fingerprint en `.gitleaksignore`.
4. **`index.html`** — dos `<link>` (claro con `media="(prefers-color-scheme: light)"`, oscuro con
   `dark`) y `<script>` de vendor y `codigo.js` antes de `app.js`; token `--bg-codigo` en los dos
   temas; CSS `.bloque-codigo`, `.linea`, `.acciones-codigo`, `.boton-copiar-codigo`, `.copiado`;
   se anula el fondo de `.hljs`.
5. **`app.js`** — `pintarRespuesta`, migración de escritores y lectores, `respuestaTerminada`,
   delegado de copiar. Verificación: `node --test` + `make check`.
6. **`Makefile`** — `test-js:  ## …` (en `.PHONY`) con `node --test "src/test/js/*.test.js"`,
   dentro de `check` y `ci`. Verificación: `make check` verde y rojo si se revierte `codigo.js`.
7. **Docs** — `docs/design-tokens.md` (fila `--bg-codigo`, renumerar las líneas de `index.html`),
   `COMPONENTS.md` (registrar «bloque de código»), `docs/architecture.md` («La interfaz web»:
   `codigo.js`, `vendor/highlight` 11.12.0 y su origen), `docs/claims-ledger.md` (fila de esa
   afirmación), `docs/design.md` (principio del bloque), `AGENTS.md` / `docs/java.md#quality-gates`
   / `docs/infrastructure.md` (CI) solo donde listan los gates: `make check` ahora exige Node (el
   CI usa el que trae ubuntu-latest, sin `setup-node`). `node scripts/verificar-enlaces.mjs`.
8. **Verificación manual (Playwright)** contra la app levantada: bloque java y uno sin lenguaje, en
   los dos temas; streaming (bloque abierto sin botón); historial restaurado; copiar → portapapeles
   igual al código, con línea vacía; `innerText` sin números; capturas para la PR.

## Criterio → verificación

| Criterio | Cómo |
|---|---|
| Bloque cercado como código, resto texto | test `partir` + Playwright |
| Resaltado por lenguaje / detectado, rótulo | Playwright (manual: depende del DOM y de hljs) |
| Números no seleccionables | CSS `user-select:none` + Playwright (`innerText` sin números) |
| Copiar exacto sin números ni rótulo | copia del string del segmento + Playwright con portapapeles |
| Streaming, final, historial; bloque abierto no rompe | tests de bloque sin cerrar/`terminado` + Playwright |
| Sin red, vendorizado | test sobre `index.html` + archivos en `vendor/` |
| Nunca HTML sin escapar | tests de info string hostil y entidades; `innerHTML` solo con salida de hljs |
| Tokens y dos temas | revisión + capturas en los dos temas |

## Riesgos

- `highlight.min.js` (~120 KB) versionado: puede disparar gitleaks (paso 3).
- Repintar el bloque abierto en cada token es O(tamaño del bloque) por token: aceptable para
  bloques de pocos KB; `highlightAuto` solo al cerrar.
- `make check` en una máquina sin Node pasa a fallar (documentado).

## Fuera de alcance

«Traducir», Markdown general (negritas, listas, código en línea), plegado y «Ajustar líneas»,
`admin.html`.

## Qué marcó cada lente y cómo se resolvió

- **Convenciones:** gitleaks sí mira el árbol (aceptado, paso 3); un segundo `escaparHtml`
  (aceptado: no hay ninguno nuevo, se usa `plaintext` de hljs); README dentro de `static/`
  (aceptado: la versión va a architecture.md + claims-ledger); cableado del target (aceptado:
  `.PHONY`, `##`, glob); renumerar design-tokens (aceptado; oscuro reutiliza `--bg-elevado`);
  patrón del delegado de copiar (aceptado); estilo de exportación IIFE (aceptado).
- **Corrección:** `textContent`/`innerText` con líneas en bloque (aceptado: líneas inline + `"\n"`,
  copia desde el string); eval contaminado (aceptado: todo lo no-código por CSS); cierre tentativo
  (aceptado, supuesto 4); `eligiendo` (aceptado, supuesto 6); inicializar `respuestaTexto`
  (aceptado); CRLF / info string / XSS (aceptado, supuestos 2–3); tests de `dividirEnLineas` que
  pasan con el bug (aceptado: decodificar entidades, spans anidados); repintado que reinicia el
  bloque (aceptado en parte: se conserva el contenedor; el reemplazo por línea se descarta por
  complejidad sin un caso medido).
- **Alcance:** criterios sin verificación (aceptado: tabla de arriba y test de red); S8 «paletas no
  son tokens» **rechazado como pregunta**: la decisión del issue ya fija «dos paletas de
  resaltado, una por tema» y el bloque sí usa tokens; repintado incremental «no pedido» (se
  mantiene acotado y probado con `primerSegmentoDistinto`, porque es lo que evita el reinicio que
  marcó corrección); COMPONENTS.md (aceptado); Node en `make check` y CI (aceptado, documentado);
  sin migración (aceptado, se dice en la PR).
