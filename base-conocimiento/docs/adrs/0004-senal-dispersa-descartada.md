# ADR-0004: La quinta señal (dispersa de BGE-M3) se descarta

## Estado

Aceptado (F2).

## Contexto

`bge-m3` puede producir, además del embedding denso de 1024 dimensiones que ya se usa, un vector
disperso (lexical weights) pensado para búsqueda tipo BM25 aprendida. El artículo de Cerebras
menciona una señal adicional de este estilo entre sus recuperadores.

Un vector disperso de BGE-M3 tiene un vocabulario potencial del tamaño del tokenizador del modelo
(decenas de miles de entradas), aunque la mayoría de posiciones sean cero para un texto dado. Los
índices HNSW de pgvector, la extensión ya elegida para la señal densa (ver
[ADR-0001](0001-tabla-unica-de-embeddings.md)), imponen un límite de 1000 dimensiones no-nulas por
vector indexado.

## Decisión

No se implementa la quinta señal dispersa. El sistema se queda con las cuatro señales que sí caben
en la arquitectura elegida: texto completo (FTS/GIN), vectorial denso (HNSW), supresión por IDF y
decaimiento por antigüedad.

## Consecuencias

- **A favor**: evita introducir una segunda extensión o un segundo motor de índice solo para una
  señal marginal, cuando las cuatro señales existentes ya cubren razonablemente los puntos ciegos
  entre sí (léxico exacto, semántica, relleno, frescura).
- **En contra**: se pierde la señal que en el artículo original ayuda específicamente con términos
  fuera de vocabulario y jerga técnica muy específica que ni el FTS clásico ni el denso capturan
  bien. Es una divergencia real respecto al diseño de referencia, no una equivalencia 1:1.
- Si en el futuro se necesitara esa señal, la opción más consistente con las decisiones ya tomadas
  no es forzarla dentro de pgvector, sino un motor de búsqueda dedicado (p. ej. un índice invertido
  aparte para pesos léxicos) — fuera del alcance de este MVP.
