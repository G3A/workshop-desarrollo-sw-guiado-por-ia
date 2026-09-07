# ADR-0001: Una sola tabla de embeddings para todas las fuentes

## Estado

Aceptado (F0).

## Contexto

El sistema indexa cuatro tipos de fuente muy distintos entre sí: documentos locales, código de
repos Git, hilos de Teams y work items/wiki de Azure DevOps. La alternativa obvia es una tabla de
embeddings por tipo de fuente (`document_chunks`, `code_chunks`, `thread_chunks`, ...), cada una
con su propio esquema afinado a su dominio.

El artículo de Cerebras señala explícitamente que su valor no está en "hacer RAG", sino en que
todas las fuentes de Slack, código y wikis conviven en una única tabla de embeddings con una única
interfaz de consulta.

## Decisión

Una sola tabla `chunks`, con `kind` como discriminador (`doc_section`, `code_block`, `thread`,
`thread_burst`, `work_item`, `wiki_section`) y `document_id`/`source_id` como referencia común.
Todas las señales de retrieval (FTS, vectorial, IDF, decaimiento) corren sobre esa única tabla.

## Consecuencias

- **A favor**: `search_unified` (la herramienta que combina todo el corpus) es un solo query, no
  una unión de N tablas. Agregar una quinta fuente no exige tocar el retrieval, solo un conector de
  ingesta nuevo que escriba en `chunks` con su propio `kind`.
- **En contra**: `chunks.metadata jsonb` carga campos que solo tienen sentido para algunos `kind`
  (p. ej. `code_references` no aplica a un `doc_section`). Es el precio de la tabla única, y es
  deliberado: el artículo lo señala como la decisión que hace posible `search_unified`.
- El límite de 1000 dimensiones no-nulas de pgvector en índices HNSW forzó descartar una quinta
  señal (dispersa) que el artículo sí usa — ver [ADR-0004](0004-senal-dispersa-descartada.md).
