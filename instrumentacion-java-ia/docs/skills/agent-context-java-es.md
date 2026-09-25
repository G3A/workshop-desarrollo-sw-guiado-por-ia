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
   material para las preguntas siguientes. Además decide si el repositorio tiene **interfaz**
   (plantillas, estáticos, un subproyecto frontend, controladores que devuelven vistas o una suite
   de navegador) y, si la tiene, lee los tokens y componentes que ya existen. Resuelve también la
   **URL web del repositorio** —con su host, preguntándole a `gh` por la URL de `origin`, no por el
   repositorio «base», que en un fork es el ajeno— y la rama de integración: la que declare tu
   `AGENTS.md`, y si no la declara, la rama por defecto. Eso es lo que la plantilla de PR necesita
   para enlazar `REVIEW.md` por URL absoluta, y por eso funciona igual en GitHub Enterprise. Si no
   hay remoto usable, deja un `TODO` en ese enlace y lo dice: no inventa la URL.
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
   escrito*, y cada uno se carga en un sitio distinto: el revisor de PRs en la nube lee `REVIEW.md`
   **en la raíz del repositorio git y en ningún otro sitio**, mientras que el `/code-review` local
   no lee `REVIEW.md` nunca y sigue `CLAUDE.md` en todos los niveles de la jerarquía. Por eso, si
   el proyecto está en una subcarpeta, `REVIEW.md` y la plantilla de PR salen a la raíz del
   repositorio aunque la skill la hayas corrido dentro de tu carpeta.

   Dos detalles que se ven poco y cuestan caro cuando faltan. **El título de `REVIEW.md` nombra el
   repositorio, no el proyecto**: el archivo vive en la raíz y puede llegar a cubrir varias piezas,
   así que titularlo con el repositorio evita que la segunda corrida deje el nombre del primer
   proyecto. Y **el enlace de la plantilla de PR es una URL absoluta**, no `../REVIEW.md`: como
   archivo en `.github/` la ruta relativa resuelve bien, pero GitHub copia la plantilla tal cual al
   cuerpo de la PR —el único sitio donde alguien hace clic en ese enlace— y ahí una ruta relativa
   da 404.
5. **Validación de afirmaciones** — antes de terminar, revisa las afirmaciones importantes que
   escribió (versión del build tool, JDK objetivo, framework de persistencia, comandos, entidades
   clave) y confirma con el usuario las que tienen baja confianza, en vez de dejarlas sin verificar.
   El resultado queda registrado en `docs/claims-ledger.md`. Cada afirmación que confirma, corrige
   o invalida la busca también al revés en `AGENTS.md` y `docs/`, y corrige ahí la frase contraria:
   una fila del registro no se propaga sola a los documentos.
6. **Verificación final** — imprime el árbol de archivos generados o modificados —diciendo a qué
   raíz fue cada uno cuando el proyecto está en una subcarpeta—, confirma que todos los enlaces
   apunten a algo que realmente existe **desde el contexto donde cada enlace se lee**, y recuerda al
   usuario cómo confirmar el trabajo con `git`. Los de `AGENTS.md` y `docs/java.md` se resuelven
   desde la carpeta del archivo que los contiene, porque se leen como archivos; el de la plantilla
   de PR se comprueba como URL, porque se lee en el cuerpo renderizado de una PR. Comprobar un
   enlace desde donde vive, en vez de desde donde se usa, es la forma de los dos fallos que esta
   verificación ya dejó pasar.

## Qué archivos toca o crea

La skill distingue dos raíces: la **del proyecto** (donde está el `pom.xml` o el `build.gradle`) y
la **del repositorio git**. Coinciden salvo que el proyecto viva en una subcarpeta de un monorepo,
y entonces cada archivo se ancla a la raíz de **quien lo lee**.

- `AGENTS.md` y `CLAUDE.md` en la raíz del proyecto: los lee el agente, que lee en todos los
  niveles de la jerarquía.
- `REVIEW.md` en la raíz del repositorio git, porque el servicio de Code Review solo lo lee ahí.
- `.github/pull_request_template.md`, también en la raíz del repositorio git —GitHub no la busca en
  una subcarpeta—, con las seis categorías como casillas y el enlace a
  `REVIEW.md`. Se escribe **una sola vez por repositorio**, nunca una por proyecto.
  **Nunca se exige como check de CI**: un workflow que obligue a marcarlas convierte
  el juicio humano en un trámite — se marcan las seis sin mirar y el registro empieza a mentir.
- `docs/business.md`, `docs/architecture.md`, `docs/data-model.md`, `docs/infrastructure.md`,
  `docs/java.md`.
- `docs/adrs/README.md`, `docs/adrs/adr-template.md` y de una a tres ADR semilla.
- Opcionalmente `docs/target-user.md`, y `EXPERIMENTS.md` en la raíz del repositorio git: el
  acuerdo sobre qué puede fallar es del equipo, no de una carpeta, y dos en el mismo repositorio es
  justo lo que hay que evitar. Solo si el usuario lo pide.
- Opcionalmente la **intención visual** —`docs/design.md`, `docs/design-tokens.md` y
  `COMPONENTS.md` en la raíz del proyecto—, que solo se ofrece si el repositorio tiene interfaz y
  el usuario la pide.
- `docs/claims-ledger.md`, con el registro de afirmaciones verificadas.

No escribe código de aplicación, no instala dependencias y no ejecuta comandos destructivos: solo
produce archivos Markdown.

## Intención visual: formato desde el repo

Un agente que ya puede abrir la pantalla con un MCP de navegador todavía necesita saber **contra
qué comparar**. Estos tres archivos son esa referencia escrita, y salen **del repositorio, no de
una escala propuesta**.

- **`docs/design-tokens.md`** registra cada token que el repositorio ya define: una fila por
  nombre y archivo de origen, una columna de valor por tema (claro, oscuro, `data-theme`…) y la
  ruta con número de línea.
- **`COMPONENTS.md`**, en la raíz del proyecto junto a `AGENTS.md` porque se lee antes de
  escribir UI —y se queda con el proyecto, a diferencia de `REVIEW.md`, porque describe la UI de
  *ese* proyecto—, registra los componentes que existen: fragmentos Thymeleaf, plantillas JTE,
  componentes de Angular, React o Vue, historias de Storybook.
- **`docs/design.md`** se queda solo con «Principios de UX», lo único que no se puede descubrir, y
  enlaza a los otros dos en vez de repetirlos.

Si el repositorio no tiene interfaz, no se ofrece ninguno de los tres, y el reporte dice qué buscó.

### La regla dura: leer, nunca proponer

**La skill nunca propone un valor**: ni escala de espaciado, ni rampa tipográfica, ni paleta, ni
roles de color. Donde el repositorio no dice nada, deja un pendiente. Inventarle a un equipo sus
decisiones de diseño es la misma alucinación que inventarle su postura de riesgo en
`EXPERIMENTS.md`. Tampoco escribe un `*.tokens.json`: lo lee si existe, pero generarlo sería
elegir un formato por el equipo.

Un repositorio de HTML y JavaScript planos no tiene estructura de componentes que descubrir:
**`COMPONENTS.md` sale con un pendiente, y esa es la salida correcta**, no una falla.

### Los tres errores que el descubrimiento no comete

1. **Buscar solo en `*.css`.** Los tokens suelen vivir dentro de `<style>` en un HTML; una
   búsqueda por extensión devuelve cero y escribe «no hay tokens», que es falso.
2. **Buscar solo `tailwind.config.js`.** Tailwind v4 define el tema con `@theme` en el CSS y ya
   no detecta ese archivo por su cuenta.
3. **Quedarse con un tema.** Un `:root` claro y otro oscuro significan dos valores por token, y
   el archivo registra los dos.

La tabla completa de señales, con la fecha en que se verificó contra DTCG 2025.10 y Tailwind v4,
está en `references/visual-intent.md`.

### Una divergencia se reporta, no se resuelve

Si los tokens están definidos en más de un archivo y las copias se separaron, la skill **no elige
cuál es la verdadera ni unifica valores**: escribe todos los orígenes y lista como hallazgo los
nombres que solo existen en uno y los valores que difieren. Elegir en silencio convertiría una
inconsistencia real en un documento que la esconde, justo lo que el agente necesita saber antes de
escribir UI.

En modo aumentar, una sección de `design.md` que era solo un pendiente se reemplaza por el enlace;
una que tiene contenido del equipo se conserva, con el enlace arriba, y el reporte avisa la
duplicación para que el equipo decida.

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

La skill solo rellena el nombre del proyecto y la rama de integración. La lista de «qué nunca es un
experimento» ya viene escrita en la plantilla, como **subconjunto declarado** de la sección
«Escalation» de `github-plan-build`: toma de ahí las filas que son **límites del permiso**
—producción, destinatarios reales, rodear una credencial que falta— y deja fuera, a propósito y
diciéndolo, las que son **gates del ciclo**: una falla de CI ambigua, un ciclo de arreglos que no
converge y una decisión de producto sin fuente de verdad, que además es reversible.

Las dos listas contestan preguntas distintas, así que no deben ser idénticas. Pedir que se copiaran
fue justo lo que hizo que una corrida real borrara «cambios que tocan autenticación, secretos o
datos de personas» por no estar en el escalamiento; esa fila se queda, marcada como lo que es: el
ciclo de entrega no se detiene ante ella, le exige `/security-review` como gate obligatorio y no
avanza en rojo. Todo lo demás queda abierto, y el reporte dice que fue a propósito.

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
