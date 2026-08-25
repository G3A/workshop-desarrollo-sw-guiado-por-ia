# ADR-0003: El texto crudo nunca entra al espacio vectorial

## Estado

Aceptado (F0-F1).

## Contexto

El enfoque más simple para RAG es embeber el texto crudo de cada chunk directo: el párrafo del
documento, el mensaje de Teams, la descripción del work item. Es lo que hace la mayoría de
implementaciones de RAG genéricas.

El artículo de Cerebras describe un problema real con ese enfoque: un hilo largo de Slack (o de
Teams, acá) mezcla la pregunta original, intentos fallidos, ruido conversacional y la respuesta
correcta. Embeber ese texto tal cual produce un vector que no representa bien "de qué trata este
hilo" — el ruido diluye la señal semántica que realmente importa para recuperarlo.

## Decisión

Antes de embeber, un LLM (`gemma3:4b`) destila cada chunk en campos estructurados:
`searchable_question`, `summary`, `resolution`, `systems_mentioned`, `code_references`. El
`embedding` se calcula sobre `distilled.searchable_question + distilled.summary`, nunca sobre
`text`. El texto crudo (`chunks.text`) sigue existiendo y alimenta la señal de texto completo
(`fts`), pero no el espacio vectorial.

## Consecuencias

- **A favor**: la señal vectorial (densa) recupera por lo que el chunk *significa*, no por las
  palabras exactas que contiene — complementa de verdad a la señal de FTS, que sí opera sobre el
  texto crudo. Si ambas embebieran lo mismo, dejarían de ser señales independientes.
- **En contra**: la destilación es una llamada a un LLM por chunk en la ingesta — un costo de
  latencia y cómputo que un enfoque naive (embeber el texto tal cual) no tiene. Se paga una sola
  vez por chunk gracias al `content_hash` (F1): si el contenido no cambió, no se vuelve a destilar
  ni a embeber.
- Un chunk cuya destilación falla (el LLM no devuelve JSON válido) queda sin `embedding` y por lo
  tanto invisible a la señal densa — recuperable igual por FTS sobre `text`, pero con una señal
  menos.
