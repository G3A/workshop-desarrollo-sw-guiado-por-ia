# ADR-0014: la extracción de documentos corre en el proceso Java y reemplaza a docling-serve

## Estado

Aceptado (2026-10-30): los tres motores son el default de `compose.yml` y `application.yml`.
Reemplaza al [ADR-0010](0010-docling-reemplaza-pdfbox.md).

## Contexto

El ADR-0010 puso a `docling-serve` como cuarto contenedor para convertir PDF, DOCX y PPTX a
Markdown. Con documentos grandes se bloquea: `jls25.pdf` (la especificación de Java SE 25,
~900 páginas) tarda **32,6 min** en CPU, y para que eso no cortara se necesitaron la API asíncrona,
el polling, la tabla `docling_tareas_en_curso` y un override de GPU con fuga de VRAM
(docling-serve#233). Se buscó un reemplazo libre, local y sin GPU que no perdiera calidad de
recuperación.

**Restricciones**: licencia Apache-2.0, MIT o equivalente; sin GPU; 100 % local; Markdown con
encabezados reales, porque de eso vive `ChunkerEncabezados`.

**Descartadas**: Marker (GPL, pesos cc-by-nc-sa), MinerU y PyMuPDF4LLM (AGPL-3), pandoc (GPL-2,
binario externo), MarkItDown (otro servicio Python), Aspose y GroupDocs (pagos). Apache Tika para
PPTX se descartó porque no emite los títulos de diapositiva (TIKA-1108).

## Mediciones

Todas en la T600 de 4 GB del taller, en CPU, con base vacía y la app del sandbox aislada.

**PDF, `jls25.pdf`** — OpenDataLoader PDF 2.5.16 en modo local contra docling-serve:

| | docling-serve | OpenDataLoader |
|---|---|---|
| Conversión | 32,6 min | 11,5 s (378 MB de heap) |
| Extraer, trocear y encolar | — | 22,5–28 s |
| Embeddings `bge-m3` | 513 s (1060 chunks) | 534 s (888 chunks) |

**Calidad de recuperación**: las 100 preguntas de `eval-100-preguntas` (82 con respuesta en la
especificación), top 5 por similitud vectorial con el mismo modelo y chunker, juzgadas a ciegas
con A/B sorteado. OpenDataLoader convierte algunas subsecciones (`4.2.1 …`) en viñetas; sin
corregirlo, docling gana 31 a 15 cara a cara (p ≈ 0,03). Con `SubseccionesNumeradas` el resultado
es un empate:

| 82 preguntas con respuesta | docling-serve | OpenDataLoader + corrección |
|---|---|---|
| Algún relevante en el top 5 | 82 % | 82 % |
| MRR | 0,723 | 0,716 |
| El top 5 alcanza para responder | 61 % | 63 % |
| Gana cara a cara (58 empates) | 16 | 8 (p ≈ 0,15) |

Los jueces tienen un ruido de ~6 puntos: la misma base de docling pasó de 55 % a 61 % en
«alcanza para responder» entre una ronda y la otra.

**DOCX y PPTX** — 60 archivos: los 44 del set de pruebas de Docling (`tests/data`) y 16 reales
del `test-data` de Apache POI (presentaciones públicas de universidades y conferencias, documentos
de reportes de bugs). Tika 4.1.0 para DOCX y Apache POI 5.5.1 para PPTX:

| | docling-serve | Tika / POI |
|---|---|---|
| Tiempo, 43 DOCX | 628,3 s (uno solo, 537 s) | 5,1 s |
| Tiempo, 17 PPTX | 8,4 s | 4,5 s |
| Errores | 0 | 0 |
| Encabezados en DOCX | 84 | 85 |
| Texto de docling que también extrae | — | DOCX 99,4 %, PPTX 93,9 % |
| Ecuaciones de Word en LaTeX | 39 | 39; 37 idénticas a docling |
| Notas del orador | no las extrae | 34 en 4 presentaciones; 12 de 12 contra el XML |

Tika y POI extraen además texto que docling no ve: encabezados y pies de página, comentarios,
datos de gráficos y notas. Las 2 ecuaciones distintas difieren solo en «…»: docling escribe
`\text{\textellipsis}`, que no es LaTeX válido en modo matemático, y aquí sale `\ldots`.

## Decisión

Convertir en el proceso de `kb-api`, en Java y en CPU:

- **PDF**: OpenDataLoader PDF (Apache-2.0), modo local, más `SubseccionesNumeradas`.
- **DOCX**: Apache Tika (Apache-2.0) con su `ToMarkdownContentHandler`, sin abrir los adjuntos
  incrustados: si no, Tika escribe el nombre de cada imagen como encabezado. Las ecuaciones
  (OMML) pasan a LaTeX antes de Tika con un port a Java del conversor de docling (MIT), que adapta
  `dwml` (Apache-2.0); van como una marca alfanumérica que se reemplaza después, porque Tika
  escapa `\ _ ^ { }`. Se convierten en todas las partes con texto: cuerpo, encabezados, pies de
  página, notas al pie y al final, y comentarios. Docling no extrae ninguna de esas partes salvo
  el cuerpo. Al final se quita la sangría que Tika escapa como `&#9;` y `&#32;` (en el set de
  prueba, 374 entidades): para el RAG no aporta y, sin escapar, volvería código al párrafo.
- **PPTX**: Apache POI (Apache-2.0), una sección `##` por diapositiva con sus tablas y notas del
  orador. El título se toma de la forma `TITLE` o `CENTERED_TITLE`: `getTitle()` de POI ignora la
  segunda y el título de portada se perdía.

Son el default: `kb.ingesta.motor-pdf=opendataloader` y `kb.ingesta.motor-office=java`. Con
`docling` en cualquiera de las dos, ese formato vuelve a docling-serve; queda para PDF escaneados
(OCR), que el corpus todavía no tiene.

**Segundo PDF largo: NIST SP 800-53 Rev. 5** (492 páginas, de dominio público), ingerido de punta
a punta con los 60 archivos de Office: 61 de 61 documentos sin error, 1235 chunks, 635 s. Mostró
dos defectos que el JLS no tenía, y los dos se corrigieron con reglas que no dependen del
documento: las mejoras enumeradas de un control (`(13) …`) salían al mismo nivel que el control
(`ChunkerEncabezados` ahora anida un encabezado que abre con un enumerador bajo el de su mismo
nivel), y frases resaltadas salían como título (`SubseccionesNumeradas` degrada un encabezado que
termina en punto o deja un `[` abierto). Chunks del capítulo de controles que conservan el
identificador del control en su ruta: 78 % → 95 %.

## Consecuencias

- **A favor**: `jls25.pdf` de punta a punta en ~8,5 min en vez de ~42; DOCX hasta 100 veces más
  rápido. Sale un contenedor de ~5 GB, y con él el polling, `docling_tareas_en_curso`, el override
  de GPU y la fuga de VRAM. Las cuatro librerías son Java puro, sin código nativo.
- **En contra**: `opendataloader-pdf-core` depende de veraPDF 1.31, que no está en Maven Central;
  el build depende del Artifactory de la Open Preservation Foundation (releases con checksum
  obligatorio). Sin OCR. El conversor de ecuaciones es código propio que hay que mantener; en
  notas al final se verificó la conversión del XML, pero no la salida de Tika, porque ninguna
  muestra traía una nota al final que Tika emita. `SubseccionesNumeradas` se validó con un solo
  PDF.
- **Encontrado en la prueba, independiente de esta decisión**: `ChunkerEncabezados` anidaba
  secciones hermanas cuando el Markdown salta niveles (corregido). El worker de embeddings lanzaba
  25 trabajos a la vez, cada uno en una transacción que retenía su conexión durante la llamada a
  Ollama, contra un pool de 10: la ingesta de `jls25.pdf` moría a los 30 s con "Failed to obtain
  JDBC Connection". Corregido: tomar el trabajo y guardar el vector van en transacciones cortas y la
  llamada al modelo queda afuera; un trabajo que lleva más de 10 min en `running` se da por
  abandonado y se retoma, en reemplazo del rollback que antes cubría una caída. Con el worker
  encendido durante la ingesta, `jls25.pdf` queda indexado en 500 s: 888 de 888 trabajos `done`,
  sin reintentos ni fallos de conexión.
- **`eval-100-preguntas` con el RAG entero**: el smoke10 (las primeras 10, con
  [ADR-0015](0015-granite-41-3b-q3km-como-llm-por-defecto.md)) dio 9 de 10 sobre la base de
  OpenDataLoader. Las 100 quedan pendientes. Para medirlo hubo que corregir el arnés: desde el
  PR #31 la UI pide elegir una reformulación cuando la búsqueda no alcanza, `ejecutar.js` no
  elegía y `reporte.js` contaba la respuesta vacía como respondida (un smoke10 con 6 vacías salía
  10/10). Ahora `ejecutar.js` acepta la primera alternativa y una respuesta vacía es un error.
- **Pendiente**: el contenedor docling-serve sigue en `compose.yml` y `kb-api` espera su
  healthcheck aunque ningún formato lo use por defecto. Sacarlo, o volverlo opcional, es el paso
  siguiente; junto con él se pueden retirar `ExtractorDocling` y `docling_tareas_en_curso`.
