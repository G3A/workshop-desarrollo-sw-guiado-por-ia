# ADR-0002: Fusión por rango (RRF), no por normalización de puntajes

## Estado

Aceptado (F2).

## Contexto

Las cuatro señales de retrieval devuelven puntajes en escalas incomparables entre sí: `ts_rank_cd`
de FTS, distancia coseno del vector, IDF crudo y un factor de decaimiento exponencial por
antigüedad. Combinarlas exige o bien normalizar cada escala a un rango común (min-max, z-score) y
sumarlas con pesos, o bien ignorar el puntaje y fusionar por la posición de cada documento dentro
de su propia lista ordenada.

Normalizar puntajes es fértil en sorpresas: un outlier en una señal (un documento con distancia
coseno casi perfecta) puede dominar la suma aunque las otras tres señales lo ubiquen bajo, y la
normalización correcta depende de la distribución de esa consulta particular, no es una constante.

## Decisión

Reciprocal Rank Fusion: `score(d) = Σ peso_señal / (k + rango_señal(d))`, con `k = 60` (el valor
que el artículo de Cerebras usa y que la literatura de RRF original recomienda). Un documento que
no aparece en una señal aporta 0 a esa señal, no penaliza. Sin normalizar ningún puntaje crudo.

## Consecuencias

- **A favor**: el consenso entre recuperadores gana sobre un primer puesto aislado y afortunado —
  un documento que sale 3º en las cuatro señales le gana a uno que sale 1º en una sola. Es
  precisamente la propiedad que una prueba `@Property` de `RrfFusionTest` verifica.
- **A favor**: agregar o quitar una señal no exige recalibrar la escala de las demás — cada una
  solo necesita producir una lista ordenada, no un puntaje comparable.
- **En contra**: se pierde la magnitud de "qué tan bueno" es un match dentro de una señal; dos
  documentos en el mismo rango de dos señales distintas cuentan igual aunque uno tenga una
  distancia coseno mucho mejor que el otro. El cross-encoder que corre después es, en parte, la
  compensación a esa pérdida de granularidad.
