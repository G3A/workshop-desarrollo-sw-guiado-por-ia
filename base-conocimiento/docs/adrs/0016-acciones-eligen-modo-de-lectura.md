# ADR-0016: las acciones sobre documentos largos ofrecen elegir cómo leerlos

## Estado

Aceptado (2026-10-30, issue #57).

## Contexto

El ADR-0013 decidió que todo documento elegido entra entero: el que no cabe en el contexto se lee
por pasadas (sub-issue #60). Con la especificación de Java (`jls25.pdf`, 888 secciones, 1,6
millones de caracteres) y Granite 4.1 3B en la T600 (ADR-0015), «Resumir» declaró 212 pasadas de
unos 3 minutos cada una: ~11 h sin aviso previo. Granite lee la entrada a ~18 tokens/s, así que el
costo está en cuánto texto entra, no en cuánto se escribe. Además, el stream no mandaba nada
durante cada pasada y el cliente cortó a los 300 s, y un tramo de 10 000 caracteres de inglés
técnico con código daba ~3700 tokens, al borde de un contexto de 4096.

Se diseñaron cuatro alternativas en mockups: A) resumir por capítulos siempre; B) solo un modo
rápido; C) un índice del documento para elegir capítulos; D) un selector con el tiempo estimado de
cada modo antes de empezar. Se eligió D.

## Decisión

- **Medir antes de suponer.** `RendimientoDelModelo` aprende del `usage` de cada llamada de
  lectura los caracteres por token y los segundos por token de entrada y de salida. El tamaño de la
  pasada sale del contexto real (`kb.llm.contexto-tokens`) menos las reservas, y el SSE manda un
  latido cada 15 s.
- **Estimar antes de empezar.** `GET /api/acciones/estimado` devuelve, sin llamar al LLM, el tiempo,
  las llamadas y las secciones cubiertas de tres modos. Si el completo pasa de
  `kb.acciones.umbral-eleccion-segundos` (600 s), la UI abre el selector; si no, la acción arranca
  como antes.
- **Tres modos** (`modo=` en la acción):
  - *Completo*: la lectura por pasadas del ADR-0013, sin cambios.
  - *Por capítulos*: el capítulo sale de la ruta de encabezados de la ingesta, probando nivel por
    nivel hasta dar entre 5 y 40 grupos. Una llamada por capítulo con un extracto acotado (hasta
    6000 caracteres repartidos entre sus secciones); la nota de cada capítulo llega a la UI apenas
    está lista. Es el modo marcado por defecto.
  - *Rápido*: k-means sobre los embeddings ya calculados elige secciones representativas hasta
    llenar la cuota (~400 caracteres cada una), y solo se hace la llamada final. Desde la UI se
    puede ampliar a por capítulos.
- La cobertura dice con qué modo se leyó cada documento y el contexto final se lo avisa al
  modelo: un extracto no se presenta como el texto completo (misma regla del ADR-0008).

## Medición

jls25.pdf, Granite 4.1 3B Q3_K_M en la T600, base extraída por OpenDataLoader (ADR-0014):

| Modo | Estimado | Real | Llamadas |
|---|---|---|---|
| Completo | ~15,4 h | no corrido | 258 |
| Por capítulos | ~66 min | 23 de 28 capítulos en 45 min (cortado) | 29 |
| Rápido | ~4 min (230 s) | 249 s | 1 |

El rápido terminó con 16 latidos y sin cortes. Su salida es más una lista de notas por sección que
un resumen, y cubrió secciones de los capítulos 3 a 15: los primeros y los últimos quedaron fuera.
La corrida por capítulos se cortó en el capítulo 23 porque al equipo le faltó memoria, no por la
aplicación. Hasta ahí tomó entre 70 y 170 s por capítulo, en línea con el estimado, y proyecta
~58 min para los 28. También mostró dos defectos, ya corregidos:

- secciones sin ruta en medio de un capítulo abrían un grupo «Sin título» y partían el capítulo
  en tres (pasó con «Interfaces», «Blocks, Statements, and Patterns» y «Expressions»);
- el modelo repetía al comienzo de la nota la línea `Capítulo: …` del prompt.

Con el primero corregido, los capítulos de jls25 bajan de 28: la cifra exacta no se volvió a medir.

## Consecuencias

- **A favor**: la persona sabe antes de empezar cuánto va a esperar y qué cubre cada opción; un
  resumen útil de un libro de 900 páginas pasa de horas a minutos. El estimado mejora solo a medida
  que el modelo se usa.
- **En contra**: por capítulos y rápido no leen todo: es una concesión explícita frente al ADR-0013,
  que se declara en la cobertura y en el contexto. El rápido depende de que el worker ya haya
  calculado los embeddings; si faltan, cae a secciones repartidas a lo largo del documento.
- **Pendiente**: correr el modo completo en segundo plano, con aviso al terminar (la otra mitad de
  la alternativa D), queda para una segunda vuelta.
