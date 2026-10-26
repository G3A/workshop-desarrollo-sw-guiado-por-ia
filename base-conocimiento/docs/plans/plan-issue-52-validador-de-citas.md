# Citas `[n]` fuera de rango como advertencia — base-conocimiento-sandbox

## Contexto

Issue real: [G3A/base-conocimiento-sandbox#52](https://github.com/G3A/base-conocimiento-sandbox/issues/52).
`Orquestador.construirContexto` numera el contexto `[1]..[k]`, pero en el camino bloqueante
(`/api/ask` y Teams) la `Respuesta` sale con `advertencias = List.of()`: si el modelo cita `[7]`
con 5 fragmentos, nadie lo nota. Este plan agrega una verificación determinista, sin segunda
llamada al LLM.

El plan pasó por una revisión adversarial en 3 lentes (convenciones, corrección, alcance) contra el
código real del repo; la tabla de hallazgos documenta qué se aceptó y qué se rechazó.

## Decisiones (del issue, textuales)

- «Una clase de `orquestacion`, `ValidadorDeCitas`, recibe el texto y `k` y devuelve los índices
  citados y los que quedan fuera de `1..k`. Solo reconoce `[n]` y `[n, m]`; no confunde `array[i]`
  ni corchetes sin número.»
- «En el camino bloqueante, una cita fuera de rango agrega a `advertencias` un texto fijo que
  nombra el índice. El texto de la respuesta no cambia.»
- «Una respuesta con `content` nulo o vacío, o el mensaje fijo de «sin información», no lanza
  excepción ni genera una advertencia falsa.»
- «La prueba en rojo se commitea antes del arreglo.»
- «Sin cambios en las firmas de `Consultar` ni de `compartido`.»

## Supuestos

1. `k = pre.citas().size()` (igual a `fragmentos.size()` cuando se sintetiza). Con
   `respuestaFija != null` («sin información», «servidor ocupado») no se valida nada.
2. `[n, m]` se generaliza a una lista separada por comas de cualquier largo, con espacios
   opcionales (`[1,9]`, `[ 9 ]`, `[1, 2, 3]`).
3. Una cadena de corchetes pegada a un identificador (`array[3]`, `m[1][2]`, `iniciado[9]`) no es
   cita; una cadena suelta sí, entera (`[1][9]` son dos citas).
4. Antes de buscar se quita el código Markdown (bloques con tres acentos graves y código inline):
   el corpus trae Java y JSON, y `[10, 20]` dentro de código no es una cita.
5. `[-1]` se reconoce y queda fuera de rango (el sintetizador estructurado arma `"%s [%d]"` con un
   `int` del modelo); `[0]` también. `[1-3]`, `[^9]`, `[9abc]`, `[1, 9abc]` no son citas.
6. Una advertencia por índice fuera de rango distinto, en orden de primera aparición, con el texto
   fijo `La cita [n] no corresponde a ninguno de los fragmentos consultados.`
7. Hasta 18 dígitos por índice (`long`); un número más largo no se reconoce como cita.

## Fuera de alcance

- Que la cita sea *fiel* (no es determinista).
- El camino en streaming (`/api/chat`, `ejecutarEnStreaming`) y `index.html`: issue aparte.
- Persistir las advertencias en `query_log`: exigiría una migración que nadie pidió.

## Pasos

1. **Rojo** — `OrquestadorTest.marcaCitaFueraDeRangoComoAdvertencia`: 2 fragmentos (rerank 9.0,
   SUFICIENTE), `Sintetizador` falso que devuelve `"X [9]"`; afirma texto intacto y exactamente la
   advertencia de `[9]`. Verificación: `./mvnw test -Dtest=OrquestadorTest` falla. Se commitea así.
2. `ValidadorDeCitas` (final, package-private, constructor privado, javadoc del porqué) con
   `validar(String, int) -> Resultado(citados, fueraDeRango)` y `advertencias(Resultado)`;
   `ValidadorDeCitasTest` con los casos borde de los supuestos 2-7.
3. Cableado en `Orquestador.ejecutar`; `advertencias().isEmpty()` en las pruebas existentes de
   «sin información» y «servidor ocupado»; comentarios y docs que afirmaban lo contrario
   (`Orquestador.java`, `Dominio.java`, `TarjetaAdaptativa.java`, `docs/architecture.md`,
   `docs/plans/plan-base-conocimiento.md`).
4. `make check` en verde. Rojo y verde se empujan juntos (el pre-push corre `make check`).
5. Tras la PR: cerrar #52 a mano — el squash a `dev` no lo cierra.

## Hallazgos de la revisión adversarial y su resolución

| # | Lente(s) | Hallazgo | Resolución |
|---|---|---|---|
| 1 | Corrección + Convenciones | Excluir el `[` precedido por `]` perdía `[1][9]` | Se excluye la cadena entera solo si su primer `[` va pegado a un identificador (supuesto 3) |
| 2 | Corrección | Código citado de las fuentes (`[10, 20]`) daría advertencias falsas | Se quita el código Markdown antes de buscar (supuesto 4) |
| 3 | Corrección | `[-1]` del sintetizador estructurado pasaba sin aviso | Se reconoce el signo y queda fuera de rango (supuesto 5) |
| 4 | Corrección | `\d{1,9}` ignoraba números grandes | Se sube a 18 dígitos con `long`; más que eso no es una cita realista |
| 5 | Corrección | `iniciado[9]` (cita pegada a la palabra) no se marca | **Rechazado**: el criterio pide no confundir `array[i]`; la exclusión es deliberada y la fija una prueba |
| 6 | Corrección + Alcance + Convenciones | El caso «sin información» no es rojo y duplicaba pruebas | El rojo es solo `"X [9]"`; las aserciones de vacío se suman a las pruebas existentes de «sin información» y «servidor ocupado» |
| 7 | Corrección | Un rojo que pase por la razón equivocada | El rojo afirma el texto exacto de la advertencia, que solo existe si el sintetizador corrió |
| 8 | Convenciones | El pre-push corre `make check`: el rojo no se puede empujar solo | Rojo y verde se empujan juntos, sin `LEFTHOOK=0` |
| 9 | Convenciones + Alcance | Afirmaciones que quedan desactualizadas | Se actualizan el comentario de `ejecutar`, el javadoc de `Respuesta.advertencias` y de `TarjetaAdaptativa`, la etapa 6 de `architecture.md` y la fila de `plan-base-conocimiento.md` |
| 10 | Convenciones | Camino hermano (`ejecutarEnStreaming`) sin validar | Se declara en el javadoc del validador y en la PR |
| 11 | Convenciones | Efecto visible en Teams (la tarjeta pinta «⚠») | Se declara en la PR |
| 12 | Alcance | `query_log` no guarda advertencias | Fuera de alcance, explícito |
| 13 | Alcance | «Una advertencia por índice» debía ser pregunta | **Rechazado como pregunta**: el issue pide «un texto fijo que nombra el índice»; queda como supuesto 6, visible en el plan publicado |
| 14 | Alcance | `Closes #52` no cierra con squash a `dev` | Paso 5: cierre a mano |
