# debt-triage

## Qué es

Triaje con criterio de hallazgos que **un analizador estático ya reportó** — no instala ni corre
un sensor nuevo (eso es trabajo de `instrument-project-java`), y no aplica el auto-fix de la
herramienta a ciegas. Para cada hallazgo abierto: lee el código, emite un veredicto (real / falso
positivo / riesgo aceptado) y propone un fix mínimo o deja escrito por qué no vale la pena
corregirlo todavía.

Es el complemento de la instrumentación determinista: `instrument-project-java` instala
Checkstyle en modo estricto, que **previene** deuda nueva; `debt-triage` **juzga** la deuda que ya
existe, con el criterio que un chequeo automático no tiene.

## Cómo se invoca

```
/sdlc-ia:debt-triage [rule id o file glob]
```

El argumento es opcional — sin él, triaja todos los hallazgos abiertos del analizador detectado.

Corre en **modo plan**: las fases 1 a 3 (detectar el analizador, traer los hallazgos y emitir cada
veredicto) no escriben nada versionable, ni siquiera cuando traer los hallazgos significa correr el
reporte del analizador y dejar su salida en `target/` o `node_modules/`. Ningún arreglo, supresión
ni issue se escribe antes de la aprobación explícita de la fase 4. En Windows, activa el skill
`windows-powershell` —si la sesión lo ofrece— antes del primer comando de shell no trivial.

La skill declara `model: opus` en su frontmatter: el triaje es criterio sobre código ajeno, que es
justamente lo que no conviene abaratar.

## Qué pasa si no hay ningún analizador conectado

Es un caso real, no un error: si la Fase 1 no encuentra ningún paso de CI, archivo de
configuración ni target de build que corra Sonar/CodeQL/ESLint/Checkstyle/PMD/SpotBugs (más allá
de la prevención en modo estricto que ya instala `instrument-project-java`), la skill lo reporta
como tal — qué buscó y dónde — en vez de fallar en silencio o inventar hallazgos. La corrida
siguiente, con un sensor real conectado, sí tiene con qué triajar.

## Fases principales

1. **Descubrir el/los analizador(es)** — busca, en orden: un paso de CI que nombre
   Sonar/CodeQL/ESLint/Checkstyle/PMD/SpotBugs/golangci-lint/ruff; un archivo de configuración en
   la raíz (`sonar-project.properties`, `.eslintrc*`, `checkstyle.xml`, `spotbugs-exclude.xml`,
   `.github/codeql/*`); un target de `make`/npm/Maven que lo corra. Si hay más de uno, pregunta
   cuál triajar — nunca elige uno en silencio.
2. **Extraer y agrupar** — trae los hallazgos abiertos y los agrupa por regla (id, severidad,
   cantidad, un ejemplo de ubicación). 🚫 **Se detiene ahí y espera:** muestra la lista agrupada
   antes de triajar ningún hallazgo individual, porque el usuario puede querer acotar la corrida a
   una severidad, un módulo o una regla, y triajar todo el backlog primero gasta el presupuesto de
   la corrida en hallazgos que nadie pidió.
3. **Triajar cada grupo** — lee el código señalado y su llamador/llamado inmediato, y decide:
   **REAL** (la regla tiene razón, propone un fix mínimo y acotado), **FALSO POSITIVO** (propone
   una supresión inline con el motivo en una frase), o **RIESGO ACEPTADO** (la regla tiene razón
   pero corregirla ahora cuesta más que el riesgo que señala — se suprime con el motivo, o se
   archiva como issue si el repo lleva deuda así). Nunca aplica un fix a todo un grupo sin leer
   cada sitio — la misma regla puede ser REAL en un archivo y FALSO POSITIVO tres líneas más
   abajo.
4. **Proponer, aprobar y recién ahí escribir** — presenta el triaje como plan: una fila por
   hallazgo con su veredicto y la acción que tomaría (el fix mínimo, la supresión con su motivo, o
   el issue a archivar). Marca todo hallazgo REAL cuyo código alrededor **no tenga ninguna prueba**
   que cubra el comportamiento que cambia: esa marca es la razón de ser del checkpoint, porque ahí
   decide el usuario —no la skill— entre un fix que nadie puede verificar y un issue. Si resolver
   un hallazgo de verdad exigiera tocar código que el hallazgo no señaló, eso entra al plan como
   seguimiento aparte, no como diff que crece en silencio. 🚫 **No escribe nada hasta el sí
   explícito.** Después del sí, `ExitPlanMode` y escribe las filas aprobadas y nada más; un
   hallazgo que el usuario sacó del plan queda sin acción y se reporta así — no se convierte
   calladamente en una supresión.
5. **Gate y reporte** — la corrida no está triajada hasta que se cumplen todas las filas del gate:
   cada hallazgo en alcance con uno de los tres veredictos (ninguno sin triajar); cada supresión
   con su motivo **inline**, junto al código; cada fix aplicado leído en su propio sitio, no en
   lote sobre el grupo de la regla; cada fix escrito presente en el plan aprobado; ningún fix REAL
   sobre código sin pruebas que no estuviera nombrado como tal en ese plan; y cero `commit`. Lo que
   queda debajo de esa línea es **informativo**: el conteo por veredicto, qué grupos de reglas
   dominan el backlog, la mezcla de severidades y cuánto del backlog cubrió la corrida. Si una fila
   del gate falla, vuelve a la fase 3 por los hallazgos que esa fila nombra — no reporta la corrida
   como triajada con una salvedad. El reporte es una tabla: hallazgo (regla + ubicación) →
   veredicto → acción tomada (corregido / suprimido con motivo / archivado como issue / sin acción
   por decisión del usuario) → por qué, y cierra con los conteos informativos.

## Qué archivos toca o crea

Depende del hallazgo, y siempre después del sí de la fase 4: el fix mínimo que cada veredicto REAL
propone, o el comentario de supresión inline junto al hallazgo. **Nunca hace `commit`** — el diff queda para que el usuario lo revise,
igual que el resto de las skills del paquete. Nunca corre un analizador nuevo que el repo no
tenga ya configurado — eso es alcance de `instrument-project-java`.

## Decisiones de diseño a tener en cuenta

- **Criterio, no auto-fix.** El fix que sugiere la herramienta es una pista, no un parche para
  pegar tal cual — siempre se lee el sitio antes de aceptar la regla.
- **Cada hallazgo se cierra con un veredicto, no con un checkbox.** REAL, FALSO POSITIVO o RIESGO
  ACEPTADO, cada uno con una frase de razonamiento, tanto en el reporte como junto a cualquier
  supresión.
- **Agrupa por regla, no por archivo.** La misma regla disparando 40 veces suele compartir una
  sola línea de razonamiento — re-derivarla 40 veces desperdicia el presupuesto de la corrida.
- **Sin fix sin red de pruebas.** Un hallazgo en código sin ninguna prueba cerca queda mejor como
  issue archivado que como un cambio de comportamiento silencioso y sin verificar. Queda en
  *propuesta* hasta el checkpoint de la fase 4, donde decide el usuario con la red faltante dicha
  en voz alta — nunca como un fix que la skill aplica por criterio propio.
- **Nunca toca un hallazgo que el diagnóstico no puede explicar.** Si no se puede decir en una
  frase por qué disparó la regla, ese hallazgo queda incierto y se marca para una persona — no se
  adivina.
