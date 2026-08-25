# ADR-0010: Docling reemplaza PDFBox como motor de extracción de documentos

## Estado

Aceptado.

## Contexto

F1 eligió Apache PDFBox para `ExtractorPdf` por ser "Java puro, sin dependencias del sistema
operativo" — a diferencia de `pdftotext`, no exige instalar nada en la imagen ni en el host, y
alcanzaba para el corpus de referencia (`.md`, `.txt`, `.pdf` con texto simple, sin tablas
complejas ni escaneos).

Al evaluar incorporar [Docling](https://github.com/docling-project/docling) esta misma sesión, la
recomendación inicial fue posponerlo, por cuatro razones: (a) exige un servicio aparte
(`docling-serve`, con modelos ML en Python) como contenedor extra; (b) el `DoclingDocumentReader`
de Arconia produce `Document` de Spring AI pensado para su pipeline RAG (`VectorStore`), que este
proyecto evita a propósito — ver "Dónde termina Spring AI" en
[`architecture.md`](../architecture.md); (c) el presupuesto de recursos del taller ya está ajustado
(~7 GB con 4 GB de VRAM disponibles, ver ADR-0009); (d) el corpus de referencia no tenía una
necesidad real pendiente de mejor extracción de tablas, OCR o formatos nuevos (DOCX, PPTX).

Esa recomendación se revierte en esta misma sesión: el producto **no tiene una primera versión
liberada todavía**, así que el costo de este cambio de arquitectura es bajo, y hay una necesidad
real y explícita de usar Docling.

## Decisión

Adoptar Docling como motor de extracción de documentos, sustituyendo `ExtractorPdf` (PDFBox).

- **`docling-serve` como cuarto servicio** en `compose.yml` (Docling es Python; no hay variante
  embebida en el proceso Java). Se suma a `db`, `ollama` y `api`.
- **Cliente**: `arconia-docling-spring-boot-starter` — el `DoclingServeApi` autoconfigurado por
  Arconia como cliente HTTP delgado. **No** se usa `arconia-dev-services-docling` (el dev service
  por Testcontainers): a diferencia de Postgres en las pruebas de integración, Arconia arranca sus
  dev services también durante `@SpringBootTest` sin pedirlo — varias pruebas de este proyecto
  bootean el contexto completo (`RecuperacionRepositorioTest` y otras, sin `classes=`), así que
  sumar esa dependencia habría hecho que `./mvnw test` bajara y levantara un contenedor de ~5 GB en
  cada corrida, sin que nadie lo pidiera. `docling-serve` se levanta como servicio real de
  `compose.yml`, igual que `db` y `ollama` ya funcionan hoy sin dev service. **No** se usa
  `arconia-ai-docling-document-reader`: ese módulo produce `Document` de
  Spring AI pensado para alimentar un `VectorStore`, exactamente la abstracción que el proyecto
  evita a propósito (un `VectorStore` no sabe expresar las 4 señales fusionadas por RRF). La
  respuesta de `DoclingServeApi` se mapea a mano a los tipos propios de `ingesta`, mismo patrón que
  `ExtractorPdf` hoy.
- **Alcance acotado a extracción, no a chunking.** Docling reemplaza el paso "convertir el archivo
  binario a texto/markdown limpio". `ChunkerEncabezados`, la destilación LLM, el bursting y el
  resto del pipeline de ingesta siguen sin cambios — Docling no reemplaza la troceada ni la
  indexación, que son la parte del diseño con valor propio.
- **Formatos**: se amplía de `.pdf` a lo que Docling soporta y es relevante para este corpus
  (`.pdf`, `.docx`, `.pptx`). `.md`/`.txt` siguen su ruta directa actual, sin pasar por Docling —
  no aportan nada nuevo ahí.

## Consecuencias

- **A favor**: mejor extracción de tablas y layout en PDFs reales que el `PDFTextStripper` plano de
  PDFBox; soporte nuevo para DOCX y PPTX sin escribir un extractor por formato; camino abierto a OCR
  para escaneados si esa necesidad aparece, sin otro cambio de arquitectura.
- **En contra**: un cuarto contenedor (`docling-serve`) con sus propios modelos ML — sube el consumo
  de RAM/disco del stack y el tiempo de arranque en frío, y es un punto de falla nuevo: si
  `docling-serve` está caído, la ingesta de `.pdf`/`.docx`/`.pptx` se detiene (hace falta decidir
  reintento/backoff en `TrabajadorEmbebido`, no fallar en silencio). Se pierde la propiedad "cero
  dependencias externas para PDF" que tenía PDFBox.
- Apache PDFBox se retira como dependencia; `ExtractorPdf` se elimina.
- **Pendiente de definir en la implementación** (no en este ADR): imagen y recursos de
  `docling-serve` (CPU vs GPU, tamaño de imagen), timeouts y política de reintento contra el
  servicio, y si arranca siempre habilitado o es opt-in como los conectores de Teams/Azure DevOps
  (`compose.yml` ya tiene ese patrón para servicios opcionales).

## Actualización — validación en vivo contra el stack real

Implementado contra `docling-serve-cpu` real (no mockeado): `docker compose up` con los 4
servicios sanos, `ExtractorDocling`/`DoclingServeApi` reemplazando a `ExtractorPdf` en
`ConectorDocumentosLocales`, `./mvnw test` en verde (99 pruebas, incluida `ArquitecturaTest`).

Tres hallazgos reales que la implementación original no anticipaba:

1. **OCR encendido por defecto es carísimo.** `docling-serve` corre OCR completo (rasteriza +
   RapidOCR) en cada página aunque el PDF ya tenga texto nativo extraíble — medido en vivo: ~1200%
   de CPU sostenido y varios minutos para un PDF sin escaneos. `ExtractorDocling` ahora manda
   `ConvertDocumentOptions.doOcr(false)` explícito en cada request: el corpus de este proyecto no
   tiene escaneados todavía (ver Contexto), así que esto es la config correcta hoy, no una pérdida
   de funcionalidad — si aparecen PDFs escaneados, vuelve a ser un flag a exponer, no un cambio de
   arquitectura.
2. **El límite de espera sincrónica por defecto (120 s, `DOCLING_SERVE_MAX_SYNC_WAIT`) no alcanza**
   ni para un PDF de varias decenas de páginas con OCR apagado — el layout y la detección de tablas
   en CPU siguen siendo el cuello de botella. Se subió a 180 s en `compose.yml`, con
   `arconia.docling.read-timeout` en `application.yml` alineado a 210 s (tiene que ser mayor: si el
   cliente corta antes que el servidor, el cliente nunca ve la respuesta 504 real). Ambos valores se
   mueven juntos — es el mismo patrón que `KB_LLM_TIMEOUT` ya usa para `llama-server`.
3. **Documentos de cientos de páginas no entran en ningún timeout sincrónico razonable.** Se probó
   en vivo con un PDF real de **892 páginas**: ni con `DOCLING_SERVE_MAX_SYNC_WAIT=600` (10 min)
   terminó. No es un bug ni una config a ajustar más — es un límite estructural de usar el endpoint
   *sincrónico* (`POST /v1/convert/source`) de docling-serve. La solución real es la API
   *asincrónica* (`convertSourceAsync`/`convertSourceBatch`, con polling de estado de tarea vía
   `DoclingServeTaskApi`), **no implementada en esta sesión** — queda como trabajo futuro si aparece
   un documento real de ese tamaño en el corpus. Validado en cambio con un PDF chico de 3 páginas
   (recorte de ese mismo archivo): ingesta de punta a punta en segundos, con encabezados Markdown
   reales llegando a `ChunkerEncabezados` — confirma que el mecanismo central de este ADR funciona;
   lo que no escala todavía es el caso extremo de tamaño de documento, no el diseño.

## Actualización — jls25.pdf confirma el límite, no es config

Se agregó `jls25.pdf` (la especificación de Java SE 25, ~900 páginas) al corpus de ejemplo y se
reintentó su ingesta subiendo `DOCLING_SERVE_MAX_SYNC_WAIT`/`arconia.docling.read-timeout` de
180s/210s a 900s/930s. Mismo resultado: 504 al agotar los 900s. Confirma en vivo el hallazgo 3 con
un segundo documento real — subir el timeout solo demora el 504, no lo evita. Los valores se
volvieron a bajar a 180s/210s (el margen que sí alcanza para el resto del corpus) y `jls25.pdf`
queda como límite conocido, sin solución hasta implementar la API async de `docling-serve`.

## Actualización — API asíncrona implementada (y validada en vivo con jls25.pdf)

Se implementó el trabajo futuro que quedó pendiente arriba. La primera versión de esta sección
describía un diseño basado en `DoclingServeApi#convertSourceBatch`
(`POST /v1/convert/source/batch`) que **no sobrevivió el contacto con un docling-serve real**: ese
endpoint devuelve 422 tanto con un `FileSource` (base64 embebido) como con un `InBodyTarget` —
solo acepta fuentes y destinos remotos (`http`/`s3`/`azure_blob`/`google_cloud_storage`/
`google_drive`), porque está pensado para convertir muchos documentos ya alojados en algún
storage, no un solo archivo embebido. Se corrigió en la misma sesión, antes de dar la
implementación por terminada. El flujo final, implementado en `ExtractorDocling`:

1. `POST /v1/convert/source/async` con `FileSource` + `InBodyTarget` (mismo cuerpo que el
   endpoint sincrónico) — responde de inmediato con un descriptor de tarea (`task_id`,
   `task_status`). Armado a mano con un `RestClient` propio, no con
   `DoclingServeApi#convertSourceAsync`: ese método de `arconia-docling` 0.29.0 llama al mismo
   endpoint pero deserializa la respuesta directo como `ConvertDocumentResponse` (confirmado en
   su bytecode) — el servidor real responde ahí con el descriptor de tarea, no con el documento,
   así que ese método simplemente no funciona contra un docling-serve real.
2. Polling con `GET /v1/status/poll/{taskId}?wait=10` hasta `SUCCESS`/`FAILURE`. También armado a
   mano: `DoclingServeApi#pollTaskStatus` recibe un `Duration` y lo manda tal cual al query param
   `wait` — Spring lo serializa en ISO-8601 (`"PT10S"`), pero docling-serve espera ahí un número
   de segundos en punto flotante y responde 422.
3. Con `SUCCESS`, `DoclingServeApi#convertTaskResult` (`GET /v1/result/{taskId}`) sí funciona tal
   cual la expone el cliente generado — es el único de los tres pasos sin bug.

**Hallazgo adicional, encontrado validando en vivo, no en el bytecode**: el `wait=10` del poll no
se puede confiar como único freno de la frecuencia de polling. Con el `wait` bien formado
(corregido el bug de arriba), docling-serve igual respondió cada poll de inmediato en vez de
bloquear hasta 10s esperando un cambio de estado real — sin control del lado del cliente, el
loop de polling golpeó al servidor a **~200-270 requests/segundo durante horas**, compitiendo por
CPU con la conversión real. Se agregó un `Thread.sleep(ESPERA_POR_POLL)` explícito del lado de
Java entre poll y poll: no depende de que el servidor bloquee de verdad. Confirmado en vivo tras
el fix: un poll cada ~10s, tal como se esperaba.

**Consecuencia en configuración**: `arconia.docling.read-timeout`/`DOCLING_SERVE_TIMEOUT` ya no
necesitan cubrir la conversión completa (ese era el problema original) — ahora acotan cada
llamada HTTP individual (submit, cada poll, fetch del resultado), todas cortas por diseño. Bajan
de 210s a 60s. `DOCLING_SERVE_MAX_SYNC_WAIT` deja de tener efecto sobre `ExtractorDocling` (solo
acota el endpoint sincrónico, que ya no se usa) y se retira de `compose.yml`.

**Validación en vivo, `docker compose up` con los 4 servicios reales**: con el fix de polling
aplicado, `jls25.pdf` (~900 páginas) se ingirió de punta a punta —
**1.833.676 caracteres de Markdown extraídos, 1060 chunks creados vía `ChunkerEncabezados` con
encabezados reales** (confirmado con una muestra del contenido: `## The Java® Language
Specification`, `## 1 Introduction`, etc.), sin error, en unos ~26 minutos de conversión real en
CPU una vez sin la contención de recursos del bug de polling. Confirma en vivo el hallazgo 3 de
la actualización anterior: el límite era el endpoint sincrónico, no el tamaño del documento — con
la API async, el mismo documento que nunca terminó ni con 900s de timeout sincrónico ahora
termina solo, sin ningún timeout artificial de por medio.

## Actualización — corrección: el relevo NO tiene esa condición de carrera; el reinicio de kb-api sí

La actualización anterior afirmaba, sin haber revisado antes `RelevadorDeFuentes`, que el relevo
automático (F8) podía disparar una tarea duplicada en docling-serve si una conversión tardaba más
que `KB_INGESTA_RELEVO_INTERVALO_MS`. Eso es **incorrecto**: `RelevadorDeFuentes` ya pone un
`ReentrantLock` (`tryLock()`) por tipo de fuente alrededor de **todos** los caminos hacia
`ConectorDocumentosLocales.ingerir()` — el relevo periódico, el botón "reindexar ahora" de F9 y
`POST /api/ingest/local-docs` (`make ingest`) pasan los tres por `relevador.relevar("local_docs")`.
Si una ingesta de `local_docs` ya está en curso, el siguiente disparo falla el `tryLock()` al
instante y devuelve `"ya hay un relevo de esta fuente en curso"` sin tocar el conector ni
docling-serve. El candado se sostiene durante toda la llamada, incluido el polling síncrono de
`ExtractorDocling`. `RelevadorDeFuentesProgramador` incluso documenta un incidente real anterior
(`duplicate key value violates chunks_orden_unico`) que este mecanismo fue diseñado para evitar.

La condición de carrera real, más angosta, es otra: ese candado vive **en memoria**, así que se
pierde si `kb-api` se reinicia a mitad de una conversión de varios minutos — la tarea queda
huérfana en docling-serve, y el próximo intento (en una JVM nueva, con el candado reseteado) no
tiene forma de saber que ya existe y manda una duplicada. Esto sí ocurrió durante el debugging en
vivo de esta sesión (reinicios manuales de `kb-api` mientras `jls25.pdf` seguía convirtiéndose).

**Solución implementada**: tabla nueva `docling_tareas_en_curso` (migración `V2`), con clave
`(source_id, external_id)` — no `document_id`, porque el documento todavía no existe en
`documents` mientras la conversión está en curso (esa fila solo se crea al terminar con éxito).
`ExtractorDocling` se partió en `submitirTarea` (envía el documento, devuelve el `taskId` de
inmediato) y `esperarYExtraer` (poll + fetch del resultado, dado un `taskId`). El orquestador
(`ConectorDocumentosLocales#extraerViaDocling`) intercala la persistencia entre los dos pasos:

1. Busca si ya hay una tarea registrada para este archivo.
2. Si no hay: somete una tarea nueva y la registra en la tabla **antes** de empezar a esperar.
3. Si ya hay: retoma esa tarea (no somete una nueva) — este es el caso de un reinicio a mitad de
   camino.
4. Al terminar (éxito o fallo definitivo) borra el registro. Si la tarea heredada ya no existe en
   docling-serve (404 — por ejemplo, docling-serve también se reinició), descarta el registro y
   reintenta una vez con una tarea nueva.

Con esto, un reinicio de `kb-api` a mitad de una conversión larga retoma la tarea existente en vez
de duplicarla — la ventana de riesgo real baja de "toda la duración de la conversión" a los
milisegundos entre recibir el `taskId` del submit y persistirlo (si `kb-api` muere justo ahí, la
tarea queda huérfana sin registro, exactamente igual que hoy sin este mecanismo — no es una
regresión, solo un caso límite mucho más angosto que no vale la pena cerrar del todo).

## Actualización — override de GPU para docling-serve (`compose.docling-gpu.yml`)

Se agregó `compose.docling-gpu.yml`, un override opcional que cambia `docling-serve` a la imagen
CUDA (`quay.io/docling-project/docling-serve-cu130:v1.29.0`) para correr layout y estructura de
tablas en GPU. No exige ninguna variable de entorno nueva: Docling detecta CUDA solo
(`AcceleratorDevice.AUTO`) apenas la imagen trae los binarios con soporte GPU.

**Por qué es un override aparte, no el default ni parte de `compose.gpu.yml`**: el punto (c) del
Contexto de este mismo ADR ya anticipaba "camino abierto a GPU... sin otro cambio de arquitectura"
— la imagen CUDA no fue el problema. Dos razones concretas para mantenerlo opcional:

1. **VRAM compartida y ya ajustada.** La T600 de esta máquina tiene 4 GB (ADR-0009), y
   `compose.bonsai.yml` ya reserva la tarjeta completa para Bonsai cuando está activo. Docling no
   publica una cifra oficial de VRAM para sus modelos de layout/tablas (son livianos comparados
   con un LLM, pero no gratis), así que combinar los dos overrides en la misma GPU es una decisión
   consciente de presupuesto, no algo para activar por defecto.
2. **Bug conocido y sin resolver en docling-serve**: la VRAM no se libera entre conversiones
   ([docling-project/docling-serve#233](https://github.com/docling-project/docling-serve/issues/233),
   reportado contra la imagen predecesora `cu124`, sin fix documentado a la fecha). En una GPU de
   4 GB compartida, suficientes ingestas seguidas pueden terminar en OOM. Mitigación actual:
   `docker compose restart docling-serve` libera la VRAM (reinicia el proceso); no hay forma de
   liberarla en caliente todavía. Si este override se usa en producción real (no solo en el
   taller), vale la pena un reinicio periódico programado de `docling-serve` como salvaguarda,
   hasta que ese issue se resuelva río arriba.

**Nota aparte, encontrada evaluando esto, no parte de este cambio**: se detectó que `llama-server`
(Bonsai, `compose.bonsai.yml`) está usando 0 MiB de VRAM en la GPU compartida ahora mismo pese a
estar sirviendo peticiones reales (medido con `nvidia-smi` dentro y fuera del contenedor, y por la
velocidad de generación degradada a ~1-2 tokens/s frente a los ~7-9 tokens/s medidos al arranque
del proceso) — parece haber caído a inferencia por CPU en algún momento de esta sesión. No
investigado a fondo: es un problema preexistente de la integración de Bonsai (ADR-0009), no de
`docling-serve`, y queda fuera del alcance de este ADR.
