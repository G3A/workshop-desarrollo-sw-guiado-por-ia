# agent-context-java

## Qué es

Genera un paquete de documentación para un repositorio Java/Spring, pensado para que un agente
de IA (o una persona nueva en el equipo) pueda entender el proyecto sin tener que leer todo el
código. Produce dos mitades en una sola corrida: el **paquete base** (`AGENTS.md`, `CLAUDE.md`,
`docs/business.md`, `docs/architecture.md`, `docs/data-model.md`, `docs/infrastructure.md`,
ADRs) y un **documento técnico de Java** (`docs/java.md`) con el grafo de módulos Maven/Gradle,
el target de JDK, la inyección de dependencias de Spring, la capa de persistencia (JPA/Hibernate
o Spring Data), los perfiles de configuración, los límites de módulo si usa Spring Modulith, los
quality gates y el pipeline de CI.

Por defecto, los documentos que genera quedan en español, sin importar el idioma en que se
converse con el agente. Puede generar la documentación en inglés si se lo pide explícitamente.

## Cómo se invoca

```
/sdlc-ia:agent-context-java
/sdlc-ia:agent-context-java en
```

Recibe un único argumento opcional para el idioma de salida: `es` (default) o `en`. En modo
aumentar, si ya hay documentación previa, prevalece el idioma de esa documentación por sobre el
argumento. Fuera de eso, trabaja sobre el repositorio en el que se ejecuta.

## Fases principales

1. **Descubrimiento silencioso** — confirma que es un repo Java (busca `pom.xml`,
   `build.gradle`, `mvnw`, etc.), detecta si ya existe documentación previa (en cuyo caso entra en
   **modo aumentar**, nunca sobrescribe), inspecciona a fondo el proyecto Java (módulos, JDK,
   persistencia, configuración) y revisa el `README` y las entidades del dominio para tener
   material para las preguntas siguientes.
2. **Entrevista** — hace alrededor de diez preguntas (menos en un repo bien documentado, más en
   uno legado y sin documentar), agrupadas en tandas: qué documentos opcionales generar, cómo
   proceder si ya hay documentación, ambigüedades que la lectura del código no resolvió, datos que
   no están en el repositorio (dónde se despliega, cómo se gestionan los secretos, el modelo de
   autenticación, el camino a producción), contexto de negocio en texto libre y reglas no obvias
   que un agente debería conocer.
3. **Borrador** — redacta cada documento a partir de plantillas en el idioma de salida resuelto
   (español por defecto, inglés si se pidió), sustituyendo los
   datos reales del repositorio. Si falta información, deja un marcador `<!-- TODO: fill in -->`
   en vez de inventar; si una sección entera no aplica (por ejemplo, no hay UI o no usa Modulith),
   la elimina en lugar de dejarla vacía.
4. **Wiring de `AGENTS.md`, `CLAUDE.md` y `REVIEW.md`** — arma `AGENTS.md` como una tabla de contenidos breve
   (menos de 80 líneas: dónde encontrar cada cosa, los comandos que de verdad se usan, las reglas
   no obvias, testing, estilo de código y seguridad) y deja `CLAUDE.md` como una sola línea que
   delega a `AGENTS.md`. Además escribe **`REVIEW.md`** —los criterios de qué mirar en un diff— y
   una **plantilla de PR** corta que enlaza a él. Son archivos distintos a propósito: `AGENTS.md`
   son las reglas que el agente respeta *al generar*, `REVIEW.md` es qué mirar en un diff *ya
   escrito*, y cada uno se carga en un sitio distinto (el revisor de PRs en la nube lee
   `REVIEW.md`; el `/code-review` local lee el archivo guía).
5. **Validación de afirmaciones** — antes de terminar, revisa las afirmaciones importantes que
   escribió (versión del build tool, JDK objetivo, framework de persistencia, comandos, entidades
   clave) y confirma con el usuario las que tienen baja confianza, en vez de dejarlas sin verificar.
   El resultado queda registrado en `docs/claims-ledger.md`.
6. **Verificación final** — imprime el árbol de archivos generados o modificados, confirma que
   todos los enlaces dentro de `AGENTS.md` y `docs/java.md` apunten a archivos que realmente
   existen, y recuerda al usuario cómo confirmar el trabajo con `git`.

## Qué archivos toca o crea

- `AGENTS.md`, `CLAUDE.md` y `REVIEW.md` en la raíz del repositorio.
- `.github/pull_request_template.md`, con las seis categorías como casillas y el enlace a
  `REVIEW.md`. **Nunca se exige como check de CI**: un workflow que obligue a marcarlas convierte
  el juicio humano en un trámite — se marcan las seis sin mirar y el registro empieza a mentir.
- `docs/business.md`, `docs/architecture.md`, `docs/data-model.md`, `docs/infrastructure.md`,
  `docs/java.md`.
- `docs/adrs/README.md`, `docs/adrs/adr-template.md` y de una a tres ADR semilla.
- Opcionalmente `docs/target-user.md`, `docs/design.md` y `EXPERIMENTS.md`, solo si el usuario lo
  pide.
- `docs/claims-ledger.md`, con el registro de afirmaciones verificadas.

No escribe código de aplicación, no instala dependencias y no ejecuta comandos destructivos: solo
produce archivos Markdown.

## `EXPERIMENTS.md` — el acuerdo, no el permiso

**La skill no cubre el permiso para experimentar y fallar**: eso es una decisión de liderazgo y va
a seguir siéndolo. Lo que cubre es **el acuerdo escrito que resulta de darlo**, y sin ese archivo
el permiso dura hasta la primera PR que salió mal.

Un equipo que solo puede usar el agente cuando está seguro de que va a salir bien no aprende a
usarlo: **aprende a esconder cuándo lo usó**.

Cinco secciones: qué puede fallar y dónde, **qué nunca es un experimento** (la frontera que hace
que el permiso se pueda dar sin miedo), qué pasa cuando sale mal, quién lo dio y cuándo se revisa,
y qué se comparte.

### La regla dura: la skill no lo contesta

**Casi todo el archivo sale con marcadores de pendiente, y esa es la salida correcta.** En los
demás documentos un `TODO` significa que el descubrimiento no alcanzó; aquí significa que **la
respuesta no está en el repositorio y no debe inventarse**. Inventarle a un equipo su postura de
riesgo es exactamente la alucinación contra la que esta skill está escrita.

La skill solo rellena el nombre del proyecto, la rama de integración, y la lista de «qué nunca es
un experimento» —copiada de la lista de escalamiento de `github-plan-build`, para que las dos digan
lo mismo y marcada como punto de partida—. Todo lo demás queda abierto, y el reporte dice que fue
a propósito.

### La cláusula que decide si el acuerdo es real

**El marcador se queda.** El trailer `Asistido-por-IA` de una PR que salió mal no se borra ni se
omite. Si desaparece de los intentos fallidos, «% de PRs con IA» mide **solo los éxitos**, y el
equipo aprendió justo lo que el archivo existe para evitar.

Y se puede comprobar: **comparar la tasa de marcado en las PRs revertidas o parchadas de urgencia
contra la tasa general**. Si son distintas, el acuerdo está escrito y no se está cumpliendo — lo
cual es información bastante más útil que el número solo.

**Tampoco es un check de CI**, por la misma razón que las casillas de la plantilla de PR: un
acuerdo convertido en trámite se firma sin leer.

## Decisiones de diseño a tener en cuenta

- **Modo aumentar, nunca sobrescribir.** Si el repositorio ya tiene `AGENTS.md`, `CLAUDE.md` o
  una carpeta `docs/` con contenido, la skill lee lo que existe y solo agrega lo que falta. Un
  árbol `docs/` preexistente no necesariamente es "suyo" — puede ser documentación propia del
  equipo (notas de arquitectura, volcados de base de datos) — así que la enlaza desde
  `docs/java.md` y `AGENTS.md` en vez de editarla.
- **Nunca inventa versiones ni detalles de esquema.** Si un dato no se puede leer del
  repositorio, queda como `<!-- TODO -->` en vez de adivinarlo.
- **`AGENTS.md` es un índice, no una enciclopedia.** El límite de ~80 líneas es deliberado: el
  contenido de fondo va en los documentos especializados, y `AGENTS.md` solo apunta hacia ellos.
- **Las ADR seed solo documentan decisiones que ya se tomaron** — por ejemplo, elección del build
  tool y el JDK, el framework de persistencia, la adopción de Spring Modulith si corresponde — y
  nunca inventan la justificación detrás de ellas.
