# Mesa redonda: qué issue llevar de punta a punta con `sdlc-ia`

Registro de la mesa del #253, 2026-10-26. Siete perfiles leyeron por separado
`G3A/base-conocimiento-sandbox`, las skills de `instrumentacion-java-ia/sdlc-ia/` y el visor
`proceso-operacional-con-ia/`, y cada uno propuso un issue. Después votaron con las siete
propuestas a la vista. Cada perfil fue un subagente de Claude Code con su rol; la moderación y la
síntesis las hice yo.

## Las siete propuestas

| | Perfil | Issue propuesto | Evidencia que citó |
|---|---|---|---|
| A | UX | Los errores de Reintentar, Eliminar y Subir de la consola se ven y se anuncian | `admin.js:251-291`: un `catch` que solo rehabilita el botón y un `alert()` |
| B | UI | El tema claro cumple el contraste WCAG AA, con un sensor JUnit | `--texto-atenuado` a 3.22:1 y blanco sobre `--acento` a 3.20:1 |
| C | Arquitectura | `allowedDependencies` de Spring Modulith: el grafo del núcleo como contrato | `ApplicationModules.verify()` solo ve ciclos e internos |
| D | Calidad | Sensor de trazabilidad: `Refs`/`Closes` y `Registro-IA` en cada commit | Cinco commits de `dev` sin issue citado |
| E | Full stack | Panel «Respuestas que no sirvieron» en la consola | `/api/admin/feedback` existe y ninguna pantalla lo usa |
| F | IA | Las citas `[n]` fuera de rango se marcan como advertencia | `Orquestador` arma la respuesta con `advertencias = List.of()` |
| G | Liderazgo de QA | Pruebas de contrato del `VerificadorGrounding` con WireMock | La clase que rechaza alucinaciones no tiene prueba propia |

## Lo que cambió el voto

Antes de votar, la moderación agregó cuatro restricciones que ninguna propuesta había pesado:

1. **Espejo.** Todo archivo que cambie en el sandbox se espeja en `base-conocimiento/` del
   monorepo, y un sensor del CI compara los dos árboles. Menos archivos, menos costo.
2. **El CI del sandbox no corre Playwright.** Una prueba de navegador queda como evidencia local,
   no como gate.
3. **El acta de la IA** (ADR-0006) va en la PR, y cada commit asistido la cita.
4. **Menos de diez minutos** para contarlo, con un antes y un después claros.

## El voto

| Perfil | Primera | Segunda | Condición para la ganadora |
|---|---|---|---|
| UX | F | B | Que la advertencia le llegue a quien pregunta |
| UI | B | F | Que el rojo inicial sea una prueba de `make ci` |
| Arquitectura | F | C | `ValidadorDeCitas` interno de `orquestacion`; sin tocar firmas |
| Calidad | F | G | `Refs #N` y `Registro-IA` en cada commit; el rojo visible en la curada |
| Full stack | F | E | Una prueba que muestre la advertencia en la respuesta |
| Liderazgo de QA | G | F | El rojo commiteado antes del arreglo |
| IA | F | G | Cada criterio con una prueba de `make ci` |

**Ganó F, cinco votos de siete.** UX y Calidad retiraron sus propias propuestas como primera
opción: A dependía de Playwright, que no es gate, y D tocaba `ci.yml`, que también se espeja.

F absorbió de G el caso de `content` nulo o vacío, y de todos la condición de commitear el rojo
antes del verde. Quedaron fuera de alcance, como issues hermanos, el camino en streaming
(`/api/chat`) y mostrar la advertencia en `index.html`.

El issue resultante es
[G3A/base-conocimiento-sandbox#52](https://github.com/G3A/base-conocimiento-sandbox/issues/52).
