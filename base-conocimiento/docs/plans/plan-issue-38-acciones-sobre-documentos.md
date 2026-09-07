# Acciones sobre documentos seleccionados — plan del issue #38

Issue: [G3A/workshop-desarrollo-sw-guiado-por-ia#38](https://github.com/G3A/workshop-desarrollo-sw-guiado-por-ia/issues/38),
con 19 sub-issues (#39 a #57) escritos por `/sdlc-ia:requirement-to-spec-java`. Rama
`feat/38-acciones-sobre-documentos` desde `dev`; el PR va contra `dev` (en este monorepo `main` es
el snapshot didáctico). Un commit por sub-issue con `Refs #<hijo>`; el cierre de padre e hijos es
manual tras CI en verde y review atendida, porque GitHub solo auto-cierra al mergear en `main`.

Este plan pasó por una revisión adversarial en tres lentes (convenciones, corrección, alcance)
contra el código real. La tabla **Hallazgos** documenta qué encontró cada lente y cómo quedó.

## Decisiones (del issue, textuales, más las dos tomadas al revisar el plan)

1. «Independencia: módulo propio que comparte solo el vault indexado y el LLM. Sin `Consultar`, sin `query_log`, sin feedback, sin reconexión tras F5.»
2. «Ubicación en la UI: dentro de la página de chat, como Opción A ampliada. El botón junto a la lista de documentos pasa a ser un menú de cinco acciones y cada resultado es un turno de la conversación.»
3. «La implementación previa de resumen (sin commit) se descarta y se parte desde cero en el módulo nuevo.»
4. «Resumir produce un resumen por documento; Sintetizar produce un único texto que integra todos los documentos (en común, contradicciones, conclusión).»
5. «Traducir documentos traduce el documento completo, sección por sección, con progreso por documento y descarga `.md`. Un documento cuyo idioma detectado ya es el destino se omite y se dice.»
6. «El traductor permite elegir origen y destino entre cualquier idioma, con detección automática del origen.»
7. «Traductor en el chat: alternativa A + B combinadas (traducir lo que escribo desde la barra de entrada, traducir lo que leo con una acción por turno), con el mismo selector de idiomas que el traductor de documentos.»
8. «Documentación a actualizar en este corte: la regla de la fachada única, las rutas sin token, y los principios de UX y la sección de resumen de la vuelta anterior.»
9. «Validación: pruebas automáticas más una demo con el corpus de ejemplo.»
10. «Destino de esta especificación: GitHub, un issue padre con un sub-issue por tarea.»
11. Lluvia de preguntas y Lluvia de ideas: «Salida estructurada, sin token a token»: el LLM devuelve JSON forzado (temas con preguntas y cita; ideas con título, justificación y cita); la UI pinta desde datos.
12. Idioma de resumen, síntesis, preguntas e ideas: «dependiendo de la selección del usuario»: el menú lleva un selector de idioma del resultado (solo destino, español por defecto, recuerda el último) y la API recibe `idioma=<ISO 639-1>`.

## Supuestos (el checkpoint existe para atraparlos)

Del issue: nombres `acciones`/`Acciones`/`/api/acciones/*` (1); GET+SSE para documentos y POST+stream leído con `fetch` para texto (2); cupo propio (3); detección de idioma con el LLM (4); lista estática de ~30 idiomas en la UI (5); presupuesto en partes iguales con redistribución (6); tope de 10 documentos (7); «Preguntar» solo rellena la barra (8); modo traducir se apaga al recargar (9); turnos en IndexedDB con `tipo` sin subir `VERSION_BD` (10); exclusión mutua por conversación (11).

Propios del plan:

13. `mensajeDeError` sale de `ChatController` a `web/MensajesDeError.java`; `documentosDe` y `proyectoDe` a `web/ParametrosWeb.java`, ahora con `NumberFormatException` traducida a `IllegalArgumentException`. `ChatController` los usa; sin cambio de comportamiento salvo que un CSV inválido en `/api/chat` pasa de 500 a 400.
14. Detección fallida (excepción, JSON inválido, código desconocido) devuelve `"und"`: se traduce igual, nunca se omite por eso.
15. Los eventos de traducción viajan en un solo `Flux<EventoTraduccion>` (interfaz sellada) que el controlador mapea a SSE.
16. Presupuesto por defecto `kb.acciones.max-caracteres-contexto=7000`: con `num_ctx` 4096, quedan ~3000 tokens tras el prompt de sistema (~350) y `maxTokens` 900 de salida; 7000 caracteres de español son ~2000 tokens. La demo lo mide (el resumen debe citar `[1]`); si sobra, se sube el default.
17. Implementación de la fachada: `Accionador` (par `Consultar`/`Consultador`). Servicios internos: `AccionesSobreDocumentos`, `TraductorDeDocumentos`, `TraductorDeTexto`.
18. Preguntas e ideas estructuradas: `Redactor.preguntar`/`idear` devuelven records de `llm` (`Preguntas(List<Tema>)`, `Ideas(List<Idea>)`) vía `useProviderStructuredOutput`; `acciones` los pasa tal cual y el SSE los manda en un solo evento `resultado`. Los marcadores de cita son enteros `n` que apuntan al documento `[n]` del contexto.
19. La UI conoce los topes por `GET /api/acciones/limites` (público, solo lectura, dos números) y deshabilita el control con «máximo N documentos» en vez de dejar que el 400 llegue como `onerror`.
20. Nombres de idioma en Java con `Locale.forLanguageTag(codigo).getDisplayLanguage(Locale.of("es"))`, sin lista propia del lado del servidor.

## Hallazgos de la revisión adversarial y su resolución

| # | Lente | Hallazgo | Resolución |
|---|---|---|---|
| 1 | Corrección, alcance, convenciones | «Al menos la primera sección de cada documento» con 10 documentos de 4000 caracteres desborda el presupuesto; 12000 + prompt + salida supera los 4096 tokens de Bonsai y Ollama trunca el prompt en silencio | La primera sección también se recorta a la cuota del documento; `contexto.length() <= presupuesto` siempre; default 7000 (Supuesto 16); prueba con 10 documentos de 4000; la demo verifica que se cita `[1]` |
| 2 | Corrección | El cupo se filtra si algo lanza entre tomarlo y construir el Flux | Orden fijo validar → cargar → recortar → tomar cupo → construir el Flux dentro de `try/catch` que libera; dos pruebas (excepción sincrónica y `Flux.error`) |
| 3 | Corrección | Detección de idioma eager antes del primer evento SSE | `Flux.fromIterable(docs).concatMap(d -> Flux.defer(...))`; prueba: sin suscripción no se llama a `detectarIdioma` |
| 4 | Corrección | Origen explícito igual al destino, y destino `auto`/`und`/texto libre inyectado al prompt | Origen == destino → omitido / texto original sin LLM; el controlador valida `^[a-z]{2,3}$` y destino ≠ `und`/`auto` → 400 antes del stream |
| 5 | Corrección | `normalizarCodigo` manda a `und` respuestas correctas como `English`, `en-US`, `spa` | Mapa de nombres comunes (español e inglés), ISO 639-2 frecuentes y `xx-YY` → `xx`; la UI cae al código crudo si no lo conoce |
| 6 | Corrección | Documentos pedidos que no existen desaparecen en silencio de la cobertura | La cobertura incluye los IDs no encontrados con `0/0` y la UI los marca «no indexado»; la etiqueta provisional se reemplaza con la del servidor |
| 7 | Corrección | `documentos=abc` produce 500 | `ParametrosWeb.documentosDe` traduce `NumberFormatException`; deduplica antes del tope; prueba `abc` → 400 |
| 8 | Corrección, convenciones | Los streams por `fetch` no calzan con `streamsActivos`; el `MutationObserver` inventa un canal de estado | Toda acción entra en `streamsActivos` con la forma `{fuente, turno, detenerContador}`, donde `fuente` para `fetch` es `{close: () => abort()}`; sin `MutationObserver`: `actualizarControlAcciones()` se llama donde hoy se toca `boton.disabled` |
| 9 | Corrección | Parser SSE sobre `fetch` sin buffer; cuelgue sin salida | Buffer hasta el último `\n\n` (acepta `\r\n`); `done` sin `fin` es error; `AbortController` con timeout |
| 10 | Corrección | No se puede guardar una traducción sobre un turno ya guardado; turnos viejos sin `tipo` | `guardarTurno` devuelve el id; `actualizarTurno(id, parche)` nuevo; `registro.tipo || "pregunta"` |
| 11 | Corrección | «Traducir» sobre un turno de más de 8000 caracteres: 400 sin cuerpo definido | El 400 devuelve `{"mensaje": …}` y la UI lo muestra en el popover; el botón B se oculta en turnos de traducción de documentos |
| 12 | Corrección | Secciones largas truncadas por `maxTokens`; bloques de código «traducidos» | Secciones de más de 1500 caracteres se parten por párrafos en bloques con progreso propio; `code_block` pasa tal cual; separador `\n\n` |
| 13 | Corrección | #45 dice «cupo por documento», el plan «una vez» | Una vez por acción, liberado en `doFinally` del Flux compuesto; se corrige #45 con un comentario |
| 14 | Corrección | `tipo` desconocido da 401 con token, no 404 | `@PathVariable Tipo` (enum); el 401 para lo demás se documenta en el javadoc del filtro |
| 15 | Corrección | Reparto sin condición de parada | El reparto ordena por largo y asigna una vez por documento; prueba «todos caben» |
| 16 | Convenciones | `Redactor` recibiendo `Acciones.Tipo` crea un ciclo de módulos y rompe «`llm` no conoce el dominio» | `Redactor` con seis métodos por nombre y helpers package-private por método; el mapeo vive en `AccionesSobreDocumentos` |
| 17 | Convenciones, alcance | «`IllegalArgumentException` → 400» no tiene mecanismo en el repo | El controlador valida antes de llamar (Bean Validation en el record del POST, topes de `Acciones.limites()`) y devuelve `ResponseEntity<Flux<ServerSentEvent<Object>>>` con `badRequest()`; la fachada no lanza por lista vacía ni por tope |
| 18 | Convenciones | Rama con número rompe `feat/<slug>` | `feat/38-acciones-sobre-documentos`: prefijo del repo, número que la skill exige |
| 19 | Convenciones | Cuatro `@Value` para un prefijo | `AccionesPropiedades` record `@ConfigurationProperties("kb.acciones")` |
| 20 | Convenciones | Mensaje de «servidor ocupado» copiado con voseo | Mensaje propio con tuteo |
| 21 | Convenciones, alcance | Documentación omitida: javadoc de `orquestacion/package-info`, `Consultar`, `web/package-info`, `Buscador`, comentario de `ArquitecturaTest`, conteos en `docs/java.md`; «13 decisiones» iba en la tarea equivocada | Sumados a la tarea 18; el conteo de ADRs pasa a la tarea 19; el grep de verificación cubre `src/main` y `src/test` |
| 22 | Convenciones, alcance | Plan persistido con nombre distinto al precedente y sin tabla de hallazgos | `docs/plans/plan-issue-38-acciones-sobre-documentos.md`, con esta tabla |
| 23 | Convenciones, alcance | `Closes #38` no cierra nada en un PR a `dev` | Cierre manual documentado arriba |
| 24 | Convenciones | Evento `idiomas` vs `idioma-detectado` | `idioma-detectado` en los dos endpoints |
| 25 | Convenciones | Núcleo emitiendo Markdown de presentación | `Texto` lleva solo la traducción; la UI pone el encabezado por documento |
| 26 | Convenciones | `localStorage` sin precedente | Con `try/catch`; «Recientes» es opcional |
| 27 | Convenciones | Dato inexacto sobre Checkstyle | Línea 120 es de Checkstyle; el corte a 100 lo hace google-java-format |
| 28 | Alcance | `KB_ACCIONES_*` no llegan al contenedor | Se agregan al bloque `environment` del servicio `api` en `compose.yml` |
| 29 | Alcance | Parser de prosa para preguntas e ideas es un invento | Decisión 11: salida estructurada |
| 30 | Alcance | `IdiomaRespuesta` en las cuatro acciones era una decisión de producto sin dueño | Decisión 12: selector de idioma del resultado |
| 31 | Alcance | Más de 10 documentos: el 400 llega como `onerror` | Supuesto 19: `/api/acciones/limites` y control deshabilitado con aviso |
| 32 | Alcance | Faltaba el paso «import a propósito → rojo» | Paso explícito en la tarea 2, anotado en el PR |
| 33 | Alcance | La demo tenía vía de escape | Sin demo, el PR queda en borrador y no se cierran #55 ni #38 |
| 34 | Alcance | `bash.exe.stackdump` seguía en el árbol | Borrado |
| 35 | Alcance | #54 pide que las citas sigan abriendo el visor | La traducción conserva `[n]` y el `<details>` de citas del turno sigue siendo el mismo; se verifica el clic |

Rechazado: ninguno de peso. El hallazgo de convenciones sobre extraer solo `mensajeDeError` se
resolvió a medias: `documentosDe` también se comparte porque el fix del CSV inválido (hallazgo 7)
aplica a los dos controladores.

## Dependencias y riesgos

- `-Werror -Xlint:all`, Spotless (google-java-format, 100 columnas) y Checkstyle (120, imports, naming) bloquean; `make format` antes de cada commit.
- `ApplicationModules.verify()`: `acciones` solo usa la API pública de `llm` y `compartido`; la fachada y sus records son `public`, el resto package-private.
- Contexto del LLM: ver Supuesto 16; la traducción va por bloques de ≤1500 caracteres.
- Playwright vive en otro repo; los smokes de UI corren desde el scratchpad contra un servidor que finge los endpoints; no son parte del repo.
- La demo necesita `KB_GPU=0 make up` y los modelos ya descargados.

## Fuera de alcance (del issue)

Feedback 👍/👎 y `query_log` para las acciones; reconexión tras F5 a mitad de una acción; Teams y `/api/ask`; traducir archivos no indexados; modo traductor de pantalla completa; persistencia del `.md` en el servidor.

## Pasos

Cada paso es un sub-issue y un commit. Verificación con los comandos del repo desde
`base-conocimiento/` (`make format`, `.\mvnw.cmd -B test "-Dtest=…"`, `make check` antes del push).

### 1. #39 — trabajo previo fuera del árbol

Hecho antes del plan: los 23 archivos quedaron en `git stash` con nombre («issue-38 tarea 1…»),
recuperables; el `stackdump` borrado. Sin commit; #39 se cierra con comentario.

### 2. #40 — módulo `acciones`, fachada y reglas de ArchUnit

Primero la prueba que falla en `ArquitecturaTest`: `accionesNoConoceElRagNiLosAdaptadores`
(`allowEmptyShould(false)`, rojo porque el paquete no existe); `acciones` en las listas de
`elNucleoNoConoceALosAdaptadores` y `compartidoEsHoja`; `because` de la primera regla con las dos
fachadas. Luego `acciones/package-info.java` y `acciones/Acciones.java` (pública):

- `enum Tipo { RESUMIR, SINTETIZAR, PREGUNTAS, IDEAS }`, `record Limites(int maxDocumentos, int maxCaracteresTexto)`
- `record CoberturaDocumento(long documentoId, String titulo, String uri, int seccionesIncluidas, int seccionesTotales)` (0/0 = no indexado)
- `record ResultadoEnStreaming(String etiqueta, List<CoberturaDocumento> cobertura, List<Cita> citas, Flux<String> texto)` para Resumir/Sintetizar
- `record ResultadoEstructurado(String etiqueta, List<CoberturaDocumento> cobertura, List<Cita> citas, Mono<Object> resultado)` para Preguntas/Ideas (el objeto es el record de `llm`)
- `sealed interface EventoTraduccion` con `IdiomaDetectado(documentoId, codigo)`, `Omitido(documentoId, codigo)`, `Progreso(documentoId, bloqueActual, bloquesTotales)`, `Texto(documentoId, fragmento)`
- `record DocumentoATraducir(long documentoId, String titulo, String uri, int bloquesTotales)`, `record TraduccionDeDocumentos(String etiqueta, List<DocumentoATraducir> documentos, Flux<EventoTraduccion> eventos)`
- `record TextoTraducido(String idiomaOrigen, String idiomaDestino, Flux<String> texto)`
- `Limites limites()`; `ResultadoEnStreaming redactar(Tipo, List<Long>, ProyectoId, String idioma)` (solo RESUMIR/SINTETIZAR); `ResultadoEstructurado estructurar(Tipo, List<Long>, ProyectoId, String idioma)` (solo PREGUNTAS/IDEAS); `TraduccionDeDocumentos traducirDocumentos(List<Long>, ProyectoId, String origen, String destino)`; `TextoTraducido traducirTexto(String texto, String origen, String destino)`

Paso explícito: import temporal de `orquestacion` en el módulo → `ArquitecturaTest` rojo → se
quita; anotado en el PR. **Verificación:** `.\mvnw.cmd -B test "-Dtest=ArquitecturaTest"`.

### 3. #41 — `SeccionesRepositorio` (prueba con Testcontainers primero, `AccionesRepositoriosTest`)

`record Seccion(long id, long documentoId, String uri, String titulo, int ord, String texto, String tipo)`;
`List<Seccion> seccionesDe(List<Long> ids, String projectId)` ordenadas por documento y `ord`,
vacío sin ir a la base si la lista es vacía; filtro por `project_id`.

### 4. #42 — `PresupuestoDeContexto` (prueba primero)

Sin Spring. `recortar(List<Long> pedidos, List<Seccion>)` → `List<DocumentoRecortado>` (los IDs
sin secciones quedan con 0/0); reparto ordenado por largo, una asignación por documento, primera
sección recortada a la cuota con «…», `contexto(...)` con `[n] título (uri)` y la línea de recorte,
longitud total ≤ presupuesto; `cobertura`, `citas` (una por documento, extracto 240) y
`etiqueta(verbo, docs)` («Resumen de 3 documentos: a, b y 1 más»). Pruebas: A entera / B parcial;
10 documentos de 4000 → total ≤ 100; todos caben; orden y duplicados; sección más larga que la cuota.

### 5. #43 — `llm.Redactor` y `RedactorOpenAi` (prueba estilo `ReformuladorOpenAiTest`)

Interfaz pública: `Flux<String> resumir(String contexto, String idioma)`, `sintetizar(...)`,
`Preguntas preguntar(String contexto, String idioma)` (`record Preguntas(List<Tema> temas)`,
`Tema(String tema, List<Pregunta> preguntas)`, `Pregunta(String texto, int fuente)`),
`Ideas idear(...)` (`Idea(String titulo, String justificacion, int fuente)`),
`Flux<String> traducir(String texto, String origen, String destino)`, `String detectarIdioma(String muestra)`.
`RedactorOpenAi`: parámetros de `SintetizadorOpenAi`; `maxTokens` 900 (resumir/sintetizar),
1200 (traducir, salida por bloque), estructurados con `useProviderStructuredOutput` y
`temperature 0`; detección con `record IdiomaDetectado(String codigo)`, `maxTokens 20`; instrucción de
idioma por código con `Locale`; helpers package-private probados sin LLM: `mensajeDeUsuario*`,
`normalizarCodigo` (`English`→`en`, `en-US`→`en`, `spa`→`es`, `klingon`→`und`), `recortarMuestra`.
Ante excepción en estructurados: `Preguntas`/`Ideas` vacías con `log.warn` (la UI muestra «el modelo
no devolvió un resultado válido»).

### 6. #44 — `CupoDeAcciones`, `AccionesPropiedades`, `AccionesSobreDocumentos`, `Accionador`

Pruebas primero: cobertura y orden; sin documentos → mensaje fijo sin LLM; cupo agotado →
mensaje propio con tuteo; cupo liberado con `Flux.error` y con excepción sincrónica del
`Redactor`; etiquetas por tipo; `estructurar` con `Mono` que libera el cupo en `doFinally`.
`AccionesPropiedades(int maxDocumentos, int maxCaracteresContexto, int maxCaracteresTexto, int maxConcurrentes)`
registrado por `@ConfigurationPropertiesScan`. La fachada no valida tamaño de lista (lo hace el
controlador con `limites()`), pero sí deduplica y tolera IDs inexistentes (0/0).

### 7. #45 — `TraductorDeDocumentos` (prueba primero)

Bloques: cada sección se parte por párrafos en bloques ≤1500 caracteres; `code_block` pasa tal
cual como `Texto`. Por documento (lazy, `concatMap` + `Flux.defer`): `IdiomaDetectado` (si origen
nulo; `und` no omite), `Omitido` si origen == destino, si no `Progreso`/`Texto` por bloque; un
solo cupo por acción, liberado en `doFinally`. Pruebas: omitido con origen detectado y con origen
explícito; secuencia de eventos; sin suscripción no hay detección; cupo.

### 8. #46 — `TraductorDeTexto` (prueba primero)

Detección solo con origen nulo; origen == destino → devuelve el texto tal cual sin LLM; cupo.

### 9. #47 — configuración

`application.yml` bloque `kb.acciones` con `KB_ACCIONES_*` y el porqué; `.env.example`;
`compose.yml` pasa las cuatro variables al servicio `api`.

### 10. #48 — `AccionesController`, utilidades de `web`, rutas sin token

Prueba primero (`AccionesControllerTest`, slice MVC con `@MockitoBean Acciones` y `limites()`
doblado): `GET /api/acciones/limites` (JSON); `GET /api/acciones/{resumir|sintetizar}` → `etiqueta`,
`cobertura`, `citas`, `token`…, `fin`; `GET /api/acciones/{preguntas|ideas}` → `etiqueta`,
`cobertura`, `citas`, `resultado` (JSON), `fin`; `GET /api/acciones/traducir-documentos` →
`documentos`, `idioma-detectado`, `documento-omitido`, `progreso`, `token`, `fin`;
`POST /api/acciones/traducir-texto` (`{texto, origen, destino}` con `@NotBlank`/`@Size(max=8000)`)
→ `idioma-detectado`, `token`, `fin`; 400 con `{"mensaje"}` para CSV inválido, sin documentos,
tope superado, códigos inválidos; `tipo` desconocido → 404 (enum en el path); error en el stream →
`error-servidor`; tokens como JSON. Luego `web/ParametrosWeb.java`, `web/MensajesDeError.java`,
`ChatController` usándolos (su test sigue verde), `AccionesController` (solo `Acciones` y
`compartido`), `ApiTokenFilter` con siete rutas y su bullet (incluido el 401 para `tipo` inválido),
`ApiTokenFilterTest` por ruta.

### 11. #49 — UI: menú de acciones y estados

`index.html`/`app.js`: control `#acciones-documentos` con menú de cinco ítems, selector de idioma
del resultado (destino), sub-panel de idiomas para Traducir (tarea 13), cuatro estados; topes
desde `/api/acciones/limites`; `actualizarControlAcciones()` en cada punto donde hoy se toca
`boton.disabled`; cierre con Escape y clic fuera. Smoke Playwright desde el scratchpad.

### 12. #50 — UI: turnos de resultado y persistencia

`nuevoTurno(pregunta, {tipo})`; turnos de acción sin feedback ni «Resultados rápidos»; bloque
«Documentos usados» con cobertura (0/0 = «no indexado»); Resumir/Sintetizar en streaming;
Preguntas/Ideas desde el evento `resultado` (temas con botón «Preguntar» que rellena la barra;
tarjetas con ⧉); todo stream registrado en `streamsActivos`; `guardarTurno` devuelve id,
`actualizarTurno` nuevo, `registro.tipo || "pregunta"`. Smoke: pintado, guardado y reload.

### 13. #51 — UI: `idiomas.js`

`window.kbIdiomas`: lista de ~30 idiomas, `crearSelector({conAuto, soloDestino, clave})`,
búsqueda, recientes en `localStorage` con `try/catch`, ⇄ que no deja «Detectar» como destino,
`nombreDe(codigo)` con fallback al código. Smoke del componente.

### 14. #52 — UI: traducir documentos

Turno «Traducción · ORIGEN → DESTINO»: filas por documento con origen detectado, progreso por
bloque, «Listo · Descargar .md» (`Blob` + `<a download>`, encabezado por documento puesto por la
UI) u «omitido»; persistencia. Smoke con SSE fingido.

### 15. #53 — UI: modo traducir (A)

Botón en la píldora, barra visible con el selector, `POST /api/acciones/traducir-texto` por `fetch`
con parser SSE con buffer y `AbortController`, registrado en `streamsActivos`; turno «Traducción»
con «Ver original»; con el modo activo nunca se abre `/api/chat`; se apaga al recargar. Smoke.

### 16. #54 — UI: traducir un turno (B)

Botón «Traducir» junto a ⧉ (oculto en turnos de traducción de documentos), popover con selector,
traducción plegable con `[n]` conservados, «Ocultar», 400 mostrado en el popover, guardado con
`actualizarTurno`. Smoke: la traducción conserva `[n]` y las citas del turno abren el visor.

### 17. #55 — demo con el corpus de ejemplo

`KB_GPU=0 make up`, tres documentos (uno largo, en total más que el presupuesto), las cinco
acciones con un idioma de resultado distinto de español al menos una vez, traductor A y B; se
anota lo observado en el PR (incluido si el resumen cita `[1]`). Sin demo, el PR queda en
borrador y no se cierran #55 ni #38.

### 18. #56 — documentación

`AGENTS.md` (regla con dos fachadas), `docs/java.md` (módulo `acciones`; conteos de
`ArquitecturaTest`), `docs/architecture.md` (tabla, «Dos fachadas», regla, sección nueva, rutas),
`README.md`, `docs/design.md`, javadoc de `orquestacion/package-info`, `Consultar`,
`web/package-info`, `recuperacion/Buscador`, comentario de `ArquitecturaTest`. Verificación:
`grep -rn "única puerta\|unica puerta\|una sola fachada\|Solo conoce" AGENTS.md docs README.md src` sin frases falsas.

### 19. #57 — ADR-0013 e índice (`AGENTS.md` pasa a «13 decisiones»)

### Cierre

`PLAN-PERSIST`: este archivo va a `base-conocimiento/docs/plans/plan-issue-38-acciones-sobre-documentos.md`
en el primer commit. Antes de cada commit `make format` y `node --check`; antes del push
`make check`; PR contra `dev` con `Refs #38`; sub-issues y padre se cierran a mano con CI en verde
y review atendida.
