# ADR-0011: Vault unificado fuera del repositorio, con panel de ingesta por archivo

## Estado

Aceptado.

## Contexto

Desde F1/F6 la ingesta local usaba dos carpetas configuradas por separado: `./corpus`
(`ConectorDocumentosLocales`) y `./repos` (`ConectorReposLocales`), ambas dentro del árbol del
repo (gitignored) y montadas en el contenedor con dos variables por lado (`KB_CORPUS_DIR`/
`KB_CORPUS_RUTA`, `KB_REPOS_DIR`/`KB_CODIGO_RUTA`). Dos carpetas configuradas por separado dentro
del propio repositorio es más superficie de la que el modelo mental de F8 ("pon los archivos en
una carpeta y olvídate") necesita, y deja contenido real (PDFs, repos clonados) conviviendo con el
código versionado, aunque esté ignorado por Git.

Además, el estado persistido de la ingesta era muy grueso: un documento existe en `documents`
(ya terminó de procesarse con éxito) o no existe (nunca se procesó, o falló — los fallos solo
quedaban en el log, nunca en la base). La consola de administración de F9 solo podía mostrar un
resumen agregado por fuente (documentos/chunks totales, último resultado del relevo), no el estado
de un archivo puntual — quien quisiera saber por qué un PDF concreto no aparece en las respuestas
no tenía dónde mirar salvo los logs del contenedor.

## Decisión

**Una sola carpeta, `vault`, fuera del repositorio por defecto (`KB_VAULT_DIR=../vault`, hermana
del repo), con dos subcarpetas fijas:**

- `vault/documentos` — lo que ingiere `ConectorDocumentosLocales` (md/txt/pdf/docx/pptx).
- `vault/repos` — lo que ingiere `ConectorReposLocales` (repos Git locales).

Subcarpetas fijas, no detección automática por la presencia de `.git`: evita ingerir dos veces el
mismo archivo (p. ej. el `README.md` de un repo clonado, una vez como código-fuente-ignorado y otra
como si fuera un documento suelto) sin tener que excluir árboles completos al escanear documentos.
Una sola variable de host (`KB_VAULT_DIR`) y una sola variable de contenedor (`KB_VAULT_RUTA`,
fija en `/vault` por `compose.yml`) reemplazan a las dos parejas anteriores.

**Estado de ingesta persistido por archivo, no solo por fuente**: tabla nueva `vault_archivos`
(migración `V3`) con una fila por archivo detectado y un estado de máquina reducido —
`detectado` → `extrayendo` (solo PDF/DOCX/PPTX, antes de someter a Docling) → `procesando` (tras
el upsert exitoso de `documents` + creación de chunks) → `error` (con el mensaje, en el catch de
cada conector). El paso final — "ya terminó de embeberse" — **no** se escribe ahí: se deriva en la
lectura comparando los chunks del documento contra los que ya tienen `embedding IS NOT NULL`,
porque el worker de embeddings no sabe nada de archivos, solo de chunks, y hacerlo escribir de
vuelta habría acoplado dos componentes que hoy no se conocen entre sí.

`AdminController` expone `GET /api/admin/vault/archivos` con el estado efectivo ya calculado
(`detectado`/`extrayendo`/`procesando`/`embebiendo (n/m)`/`listo`/`error`), y la consola
(`admin.html`/`admin.js`) lo muestra como una tabla estilo *Job Runner* que se refresca sola
(polling cada 3 s) — errores y trabajo en curso primero, con un botón "Reintentar" por archivo en
error. El reintento no necesita un endpoint nuevo: un archivo en error nunca llegó a `documents`,
así que el próximo relevo (automático, o el "Reindexar ahora" que ya existía por tipo de fuente) lo
reprocesa solo; el botón de la UI llama a ese mismo endpoint existente.

`ConectorReposLocales` gana además aislamiento de fallos por archivo (antes no lo tenía: un archivo
problemático tumbaba el repo entero), mismo patrón que `ConectorDocumentosLocales` ya usaba desde F1.

## Consecuencias

- **A favor**: un único lugar para configurar y montar, sin la confusión de dos pares
  `HOST_DIR`/`RUTA_CONTENEDOR`. El contenido real deja de convivir con el árbol versionado incluso
  de forma ignorada. Quien pregunte "¿por qué no aparece mi PDF?" tiene una respuesta visual en
  segundos, con el mensaje de error real, en vez de tener que ir a los logs del contenedor.
- **En contra**: el vault por defecto ya no vive junto al repo dentro del mismo árbol de carpetas,
  así que clonar el repo ya no trae consigo un lugar obvio para el contenido — hay que crear
  `../vault/documentos` y `../vault/repos` a mano (o cambiar `KB_VAULT_DIR`) antes de la primera
  ingesta, y copiar ahí el contenido de una instalación existente.
- **No implementado a propósito**: push por WebSocket/SSE para el panel (el polling de 3 s alcanza
  a la escala de un taller) y cancelación de un archivo en curso.
- **Corregido durante la implementación**: la primera versión renombró también `sources.name` de
  `"corpus"` a `"documentos"`, para que el identificador lógico combinara con la nueva carpeta. Se
  revirtió: el emparejamiento incremental (`content_hash`, "¿este archivo ya lo tengo?") se hace por
  `(source_id, ruta_relativa)`, así que un `sources.name` distinto crea una fuente nueva sin
  historial — CUALQUIER archivo, cambiado o no, parece nuevo, y fuerza reingerir y reextraer con
  Docling cada PDF/DOCX/PPTX ya procesado. Verificado en vivo con `jls25.pdf` (900 páginas): el
  renombre disparó una reextracción de ~15-25 min completamente evitable, solo por mover la carpeta
  de sitio con el mismo contenido. `sources.name` se queda en `"corpus"` a propósito, aunque la
  carpeta ya se llame `documentos` — es un identificador interno, no algo que el usuario vea.
