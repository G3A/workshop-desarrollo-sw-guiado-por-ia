# legacy-test-harness

## Qué es

Acondiciona un repositorio legacy — arquitectura desconocida, pruebas escasas o ausentes — para
hacer crecer **pruebas reales sobre código real que ya está en producción**, una capa y un tramo
por vez. Censa los actores y mapea las costuras primero, al estilo Feathers, antes de tocar nada:
una costura que solo se puede cortar editando código de producción se propone y se archiva como
issue aparte, nunca se aplica inline.

Dos reglas se sostienen en toda la skill: el sujeto de cada prueba generada es una clase o función
que ya está en producción (un esqueleto autocontenido que nunca toca código real no cuenta como
capa generada), y cero cambios de producción sin aprobación explícita.

## Cómo se invoca

```
/sdlc-ia:legacy-test-harness [path] [capa,...]
```

Ambos argumentos son opcionales — sin `path`, opera sobre el repo completo (pero igual acota el
tramo en la Fase 4); sin capas, pregunta cuáles generar en esta corrida.

## Corre en modo plan, y se detiene tres veces

Las fases 1 a 5 corren dentro de `EnterPlanMode` y **no escriben nada versionable**. Los
diagnósticos que dejan `target/` o `node_modules/` sí corren ahí: eso no es versionable. Los tres
STOPs están donde tú tienes algo que decidir, no donde a la skill le conviene preguntar:

1. **El mapa de costuras** (Fase 3) — ves exactamente qué haría falta tocar, antes de que exista
   una sola prueba. Es el corazón del «menor impacto».
2. **La selección de capas y el tramo** (Fase 4) — nunca asume «las cinco capas, todo el repo».
3. **El plan completo** (Fase 6) — recién después de tu sí sale del modo plan y escribe.

## Las cinco capas

Colaboración/unidad, contrato, aceptación, rendimiento y seguridad — el mismo vocabulario de capas
que usan `pruebas-de-unidad`, `pruebas-de-contrato`, `pruebas-de-aceptacion`,
`pruebas-de-rendimiento-k6` y `pruebas-de-seguridad` en este mismo taller, aplicado acá a un
repositorio que todavía no las tiene.

Cada capa tiene **dos varas distintas**, y confundirlas es como una corrida aprueba su propio gate
con el censo a medias: la **meta** es el censo (o el tramo acordado), y el **piso** es el mínimo de
calidad de esa capa. Cumplir el piso con el censo a medias no pasa el gate.

## Las ocho fases

1. **Huella** — detecta el/los stack(s), la herramienta de build y el framework de pruebas por
   stack. Si ya existe una estrategia de pruebas real, esta es una corrida **incremental**: lee el
   apéndice de censo del ADR anterior, lo refresca contra el código actual y genera solo lo que
   falta.
2. **Preflight** — comprueba que el terreno **ejecuta** antes de planear nada encima: el runtime
   que el build exige (fijado *antes* de compilar), un baseline compilable, la suite existente con
   su línea base de fallos, si hay runtime de contenedores, y si el feed de dependencias resuelve
   las dependencias de test que las capas van a pedir. Cada bloqueo marca su capa en el menú de la
   Fase 4 como «bloqueada por entorno»: se propone como issue, no se promete.
3. **Censo y mapa de costuras** — el censo exhaustivo y numerado de actores con lógica de negocio,
   no «los relevantes», y para cada uno sus bordes de I/O clasificados 🟢 enganchable hoy / 🟡
   requiere costura / 🔴 irreducible. Cada 🟡 y 🔴 anota su characterization test de
   acompañamiento. **STOP: te presenta el mapa.**
4. **Tramo y capas** — el menú de las cinco capas con las bloqueadas ya marcadas, las dependencias
   entre capas advertidas, y un **tramo** acordado si el censo pasa de unos 30 actores 🟢. **STOP:
   espera tu selección.**
5. **Consolidar el entregable** — todavía sin escribir: el ADR de estrategia de pruebas con el
   censo y el inventario de dobles como apéndices, el layout raíz-por-tipo, las pruebas por capa
   con sus pisos, las redes de characterization, la lista de costuras a aprobar y los gates de
   pipeline.
6. **Aprobación y escritura** — **STOP: no escribe nada hasta tu sí explícito.** Después escribe
   solo en rutas de test, config de pipeline y la superficie de test del build file, por lotes de 3
   a 5 actores, con dos reintentos por actor antes de marcarlo bloqueado.
7. **Gate de realidad** — obligatorio. Valida que lo escrito prueba código real; un esqueleto
   autocontenido siempre compila y siempre pasa, y ese falso verde es justo lo que esta fase existe
   para cazar.
8. **Reportar y cerrar** — el bloque de cierre con el censo por estado, las capas generadas,
   parciales y bloqueadas, el resultado del gate, dónde quedó el ADR, y cada costura como candidata
   a issue con el comando listo para pegar.

## Los cinco estados del censo

Al cierre, cada actor lleva exactamente uno: `cubierto`, `excluido` (con su justificación
escrita), `pendiente-de-costura` (🟡, con su red), `bloqueado` (🔴 o entorno, con su diagnóstico) o
`pendiente` (fuera del tramo acordado). La red de characterization **no** convierte un
`pendiente-de-costura` en `cubierto`: fija lo que el código hace hoy, no lo que debería hacer.

Ese apéndice del ADR es lo que hace la corrida **reanudable** — es lo que lee el modo incremental
la próxima vez.

## Qué archivos toca o crea

Archivos de prueba nuevos por cada capa generada, bajo la convención de test del stack detectado, y
el ADR de estrategia de pruebas en el `docs/adrs/` del repo objetivo. **Nunca edita código de
producción** — donde una costura lo requeriría, la registra como candidata a issue en vez de
tocarla. La única parte editable del build file es su **superficie de test**: dependencias con
scope de test, plugins de test, el perfil opt-in, y plugins de solo reporte. **Nunca hace
`commit`** — el lote queda para que lo revises, igual que el resto de las skills del paquete.

## Cuándo aplica (y cuándo no)

Es la skill del nodo `bt` del proceso operacional — el camino que se toma cuando «crear estrategia
de pruebas» (nodo `GT`) resuelve «no». Un repo que ya tiene una estrategia de pruebas sólida (como
`base-conocimiento`, con ArchUnit, Testcontainers, Mockito, AssertJ, jqwik y WireMock ya
instalados) resuelve `GT` en «sí» y nunca llega a esta skill — no hace falta correrla ahí. Su caso
de uso real es un repositorio legacy sin ese arnés todavía.

## Decisiones de diseño a tener en cuenta

- **Mapear antes de tocar.** Un mapa de costuras armado sin correr nada es barato de tener mal; una
  edición de producción no lo es.
- **Un censo, no una muestra.** «Los actores relevantes» es exactamente como una corrida reporta
  éxito habiendo cubierto el 7% del repositorio. Sin denominador no hay totalidad que medir, y
  decidir que un actor no necesita pruebas es una exclusión registrada, no un actor que nadie miró.
- **La inyección por campo es 🟢, no 🟡.** Un colaborador que vive en un campo inyectado se puebla
  por reflexión desde la prueba, sin tocar producción. Clasificarlo como «requiere costura» es el
  error que deja sin pruebas capas enteras de cualquier legacy con inyección de dependencias — por
  eso la advertencia vive en el cuerpo de la skill y no en una reference.
- **Un tramo por vez.** Los repos legacy son grandes por definición — generar todas las capas de
  todo el repo en una sola pasada produce un diff que nadie puede revisar. Sin tramo explícito la
  vara sigue siendo el censo completo: olvidar pactarlo no encoge en silencio lo que se comprueba.
- **Cada costura propuesta lleva su red.** Una costura sin characterization test es un refactor a
  ciegas para quien tome ese issue después. El golden master pinna lo que **es**, bugs incluidos, y
  es temporal: pinnar → aplicar la costura → escribir las pruebas reales → retirarlo.
- **Una capa generada es una capa probada.** El gate de la Fase 7 no comprueba que haya un import
  de producción: comprueba que **resuelva** a un archivo bajo la raíz de producción. Con el layout
  raíz-por-tipo, `unidad/` y `testutil/` comparten paquete raíz con producción, así que un
  esqueleto que solo importa `testutil.FakeX` pasaría un grep por prefijo de paquete.
- **El lote verde es relativo a la línea base.** Cero fallos **nuevos** contra lo que ya fallaba en
  el preflight, no cero fallos: los rojos preexistentes quedan como hallazgo previo y no se
  arreglan ni se deshabilitan de paso.
