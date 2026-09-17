# ADR-0003: El ADR es la fuente de datos del sensor del espejo

## Estado

Aceptado — 2026-09-17. **Reemplaza al
[ADR-0002](0002-equivalencia-entre-el-monorepo-y-el-sandbox.md)**, que decidió dónde vive la lista
de divergencias; este decide que además la lee una máquina.

## Contexto

El ADR-0002 decidió que la relación espejo entre `base-conocimiento/` del monorepo y
`G3A/base-conocimiento-sandbox`, y su lista de divergencias deliberadas, se registran en un ADR de
la raíz del monorepo. **Esa decisión sigue en pie y no se revisa aquí.** Lo que cambia es lo que el
propio 0002 dejó anotado como su condición de reemplazo:

> **Qué haría reconsiderar**: […] o que aparezca un sensor que compare los dos árboles en CI. Lo
> segundo es lo que convertiría esta lista en una regla verificable en vez de un acuerdo escrito, y
> sería un ADR nuevo que reemplace a este, no una excepción silenciosa.

El issue #155 construyó ese sensor, así que el disparador se cumplió al pie de la letra. El 0002
también nombraba el hueco en sus consecuencias: «esta lista envejece: hay que actualizarla en el
mismo PR que cree o cierre una divergencia, y **nada lo verifica a máquina**».

**El fallo concreto que esto ataja.** En el paso A del issue #144 se dio por hecho que el sandbox no
tenía el sensor de enlaces. Era falso —lo tiene, con el mismo blob— y el error fue hacer `grep`
sobre un clon que estaba 15 PR atrás del remoto. Dos de las tres lentes de la revisión adversarial
lo corrigieron, pero estuvo a punto de entrar al plan como un hecho, y con él una divergencia
silenciosa. Es la clase de cosa que un texto no atrapa: el ADR **sí** decía que la lista hay que
mantenerla, y aun así el que la mantiene fue el que se equivocó. `REVIEW.md` lo dice en una línea:
«si un ítem de esta lista se puede comprobar con una máquina, deja de ser texto y se vuelve sensor».

Opciones evaluadas para **dónde vive la lista que el sensor lee**:

1. **Duplicada dentro del script**, como un arreglo de rutas. Es el defecto por el que el 0002 ya
   había descartado el `claims-ledger`: dos copias de la misma lista se desincronizan, y la que
   manda pasa a ser la que nadie lee.
2. **Un archivo de datos aparte** (`espejo.json` junto al sensor), con el ADR citándolo. Parseo
   trivial, pero parte en dos el registro: el dato en un sitio y su razón en otro, que es
   exactamente lo que el 0002 existe para evitar.
3. **El ADR mismo, parseado** — este archivo. La lista y su razón siguen juntas y en prosa legible;
   lo único que se agrega es que las tres categorías queden delimitadas para una máquina.

## Decisión

**Opción 3: este ADR es la fuente de datos de `scripts/verificar-espejo.mjs`.** No hay segunda
copia de la lista. Si el ADR y los árboles discrepan, uno de los dos está mal, y eso es justo la
señal que el sensor existe para dar.

Las tres categorías van delimitadas por marcadores HTML, invisibles al leer y estables ante
cualquier reescritura de la prosa que las rodea:

```
<!-- espejo:difieren-por-contenido -->  …  <!-- /espejo:difieren-por-contenido -->
<!-- espejo:solo-en-el-sandbox -->      …  <!-- /espejo:solo-en-el-sandbox -->
<!-- espejo:pendientes-de-limpieza -->  …  <!-- /espejo:pendientes-de-limpieza -->
```

Tres reglas de escritura que no son de estilo, sino lo que hace parseable el archivo:

- **Una ruta literal por entrada, sin globs.** `docs/adrs/0012-*.md` no es igual a ninguna ruta de
  ningún árbol: se reportaría a la vez como entrada huérfana y como archivo sin explicar. Cuidado
  con la trampa: la raíz del monorepo tiene **nueve** `scripts/agent-hooks/*.sh` y el sandbox
  **siete**, así que expandir ese glob copiando el listado local mete dos nombres que no existen
  allá.
- **Ningún conteo en la prosa.** «9 archivos», «Ocho archivos», «los siete» son estado duplicado que
  envejece; el sensor los imprime en cada corrida. Además evita que un lector automático confunda
  «los **cuatro** límites del permiso» con el largo de una lista.
- **La razón va después de la ruta, tras un guion largo.** Puede ocupar varias líneas: la entrada
  termina donde empieza la siguiente.

### Qué se espeja

Todo `base-conocimiento/` del monorepo ↔ la raíz del sandbox, por hashes de blob. La receta a mano:

```
git ls-tree -r origin/dev -- base-conocimiento | sed 's|^base-conocimiento/||'   # en el monorepo
git ls-tree -r origin/dev                                                        # en el sandbox
```

El `sed` no es adorno: sin él, el listado del monorepo prefija cada ruta y la comparación marca como
distintos el 100 % de los archivos. **Y va anclado con `^`**: sin el ancla,
`base-conocimiento/docs/plans/plan-base-conocimiento.md` —que existe— se convierte en
`docs/plans/plan-.md`, una ruta que no existe en ningún lado.

**El clon local puede estar atrasado.** Por eso la receta dice `origin/dev` y no `dev`, y por eso va
precedida de un `git fetch`; para una pregunta puntual —«¿el sandbox tiene este archivo?»— es más
directo preguntarle al remoto:
`gh api repos/G3A/base-conocimiento-sandbox/contents/<ruta>?ref=dev`.

**La receta y el sensor no miran el mismo ref, a propósito.** La receta compara `origin/dev` contra
`origin/dev` porque es lo que uno quiere saber a mano. El sensor compara el árbol **de la corrida**
—en una PR, el merge commit, que es el que quedará en `dev`— contra `sandbox@dev`, e imprime el ref
y el SHA que usó, para que un rojo se pueda reproducir.

### Qué difiere a propósito

**Difieren por contenido, y no deben sincronizarse:**

<!-- espejo:difieren-por-contenido -->

| Archivo | Por qué |
|---|---|
| `AGENTS.md`, `README.md` | Estructura monorepo vs. standalone. El del sandbox lleva además el aviso de que su `main` es el snapshot «antes de instrumentar con IA» y no se mergea. |
| `docs/claims-ledger.md` | Referencia números de issue que no coinciden entre repos. |
| `.gitignore`, `.gitleaks.toml`, `.gitleaksignore` | Huellas y rutas propias de cada repo. |
| `docs/infrastructure.md`, `docs/java.md`, `docs/adrs/0012-spring-modulith-para-fronteras-entre-modulos.md` | Mismo texto con las rutas del monorepo reescritas para el standalone, y el conteo de hooks y la ubicación del CI, que difieren de verdad. |

<!-- /espejo:difieren-por-contenido -->

**Existen solo en el sandbox, por la forma del repositorio.** En el monorepo sus equivalentes viven
en la raíz del repositorio, una carpeta más arriba de `base-conocimiento/`, así que la comparación
de árboles los ve como ausentes sin que falte nada:

<!-- espejo:solo-en-el-sandbox -->

- `.claude/settings.json` — Claude Code solo lo busca en la raíz del repositorio git.
- `.mcp.json` — misma razón que el anterior.
- `lefthook.yml` — lefthook solo lo busca en la raíz; cada comando usa `root:` para acotarse.
- `.github/workflows/ci.yml` — Actions solo lee workflows en la raíz del repositorio.
- `scripts/agent-hooks/_lib.sh` — los hooks del agente son del repositorio entero. En el monorepo la
  carpeta tiene nueve; estos siete son los que el sandbox también tiene.
- `scripts/agent-hooks/audit-log.sh` — hook del agente, como el anterior.
- `scripts/agent-hooks/block-dangerous-bash.sh` — hook del agente, como el anterior.
- `scripts/agent-hooks/dependency-sweep.sh` — hook del agente, como el anterior.
- `scripts/agent-hooks/generated-files-guard.sh` — hook del agente, como el anterior.
- `scripts/agent-hooks/secret-read-guard.sh` — hook del agente, como el anterior.
- `scripts/agent-hooks/version-pin-guard.sh` — hook del agente, como el anterior.
- `REVIEW.md` — desde #141. Lo lee el servicio de Code Review desde la raíz del **repositorio**.
- `.github/pull_request_template.md` — desde #141. Lo lee GitHub desde la raíz del repositorio.
- `EXPERIMENTS.md` — desde #141. Lo lee el equipo desde la raíz del repositorio.
- `scripts/verificar-enlaces.mjs` — desde #144. Cubre los `.md` de todo el monorepo, así que se
  mudó a la raíz; en los **dos** repos vive en esa misma ruta respecto de su propia raíz.

<!-- /espejo:solo-en-el-sandbox -->

**`REVIEW.md`, `.github/pull_request_template.md` y `EXPERIMENTS.md` son el caso que más confunde**:
existen en los dos repos, con el mismo contenido salvo lo que se dice abajo, y aun así la receta los
lista como «solo en el sandbox». La regla que decide dónde va cada archivo está en
`instrumentacion-java-ia/sdlc-ia/skills/agent-context-java/references/monorepo-roots.md`: cada
archivo se ancla a la raíz de **quien lo lee**. En el monorepo la raíz del repositorio y la del
proyecto son distintas; en el sandbox son la misma.

**Eso deja de ser prosa y pasa a ser la regla que separa esta lista de la siguiente:** toda entrada
de aquí tiene que existir en la **raíz del monorepo**, y el sensor lo comprueba. Una que no exista
allá no es una divergencia por forma del repositorio: es un archivo que solo tiene el sandbox, y va
en la lista de pendientes o no va en ninguna.

**Las diferencias deliberadas de los tres archivos de la raíz, desde #141:**

- el `REVIEW.md` del sandbox no lleva el bloque condicional del preámbulo ni el prefijo
  `base-conocimiento/` en sus ítems, y no menciona el plugin ni `playbook-sdlc-ia/vendor/`, que allá
  no existen;
- la plantilla de PR difiere en dos puntos: su comentario final, porque el sandbox mergea por squash
  con el mensaje **por defecto** y el monorepo con el cuerpo de la PR; y el enlace a `REVIEW.md`,
  que es una URL absoluta al propio repositorio y por construcción nunca puede ser idéntica. La URL
  absoluta no es capricho: una ruta relativa **404** en el cuerpo renderizado de una PR, que es el
  único sitio donde alguien hace clic en ese enlace. Desde el issue #146 la plantilla que genera
  `agent-context-java` ya emite la URL absoluta, así que volver a correr la skill sobre cualquiera
  de los dos repos conserva esta divergencia en vez de reintroducir el enlace roto;
- el `EXPERIMENTS.md` del sandbox cubre un solo proyecto y cita la skill por URL, no por ruta, y no
  lleva el comentario de procedencia que el del monorepo sí tiene.

Los **cuatro límites del permiso** de su sección 2 son los mismos en los dos, palabra por palabra:
son del equipo, no del repositorio.

**`scripts/verificar-enlaces.mjs` es el único de esta lista con una afirmación de contenido**, y por
eso es el único que el sensor compara byte a byte: los dos repos tienen **el mismo cuerpo de
script** y difieren solo en la cabecera de comentarios —la del sandbox nombra los issues de este
repositorio como espejo y no habla del plugin, que allá no existe—, así que es una divergencia de la
misma clase que la de `docs/java.md`. Espejado con el PR #44 del sandbox. La comprobación es el
cuerpo desde el primer `import` en adelante; lo de arriba es cabecera y puede diferir.

### Qué no es divergencia, sino pendiente

Archivos de salida sueltos en `scripts/` del sandbox sin contraparte en el monorepo. No son una
diferencia decidida: son residuos de corridas, de la misma clase que el `scripts/.env` que la PR #39
del sandbox ya sacó. **Quedan anotados como pendientes de limpieza**, no como divergencia legítima,
para que la comparación de árboles no los lea como acordados:

<!-- espejo:pendientes-de-limpieza -->

- `scripts/error-api.txt` — volcado de una corrida de `make capturar-error`.
- `scripts/kb-ollama-select-string.md` — salida de un diagnóstico de Ollama.
- `scripts/ollama-ps.txt` — salida de un `ollama ps`.
- `scripts/resultado-diagnostico.md` — salida de una corrida de diagnóstico.
- `scripts/resultado-make-check` — salida de una corrida de `make check`.
- `scripts/resultados-make-up.md` — salida de una corrida de `make up`.
- `scripts/resultados-make-verificar.md` — salida de una corrida de `make verificar`.
- `scripts/revisar.txt` — notas sueltas de una revisión.

<!-- /espejo:pendientes-de-limpieza -->

Esta lista **está pensada para vaciarse**, y el sensor lo trata así: que un residuo desaparezca del
sandbox es el resultado deseado, no un error, y que la lista quede sin entradas tampoco lo es. Lo
que sí es un error es que falte el marcador. La regla complementaria a la de arriba: ninguna entrada
de aquí puede existir en la raíz del monorepo — si existe, no era un residuo.

### Qué hace el sensor con lo que encuentra

`node scripts/verificar-espejo.mjs`, en el job `check` del CI. Las severidades son la parte que hace
vivible un gate acoplado a otro repositorio:

| Caso | Resultado |
|---|---|
| Difiere por contenido y no está en la lista | **rojo** |
| Existe solo en el sandbox y no está en ninguna lista | **rojo** |
| Existe solo en el monorepo | **rojo** — «falta espejar» |
| Una entrada de `solo-en-el-sandbox` no existe en la raíz del monorepo | **rojo** — está mal clasificada |
| Una entrada de `pendientes-de-limpieza` sí existe en la raíz del monorepo | **rojo** — no era un residuo |
| El cuerpo de `scripts/verificar-enlaces.mjs` difiere entre los dos repos | **rojo** |
| Una entrada del ADR que ya no corresponde a ninguna diferencia real | aviso |
| `pendientes-de-limpieza` encogiendo o vacía | aviso |
| La misma ruta dos veces dentro de una lista | **rojo** |
| La misma ruta en `solo-en-el-sandbox` y en `pendientes-de-limpieza` | **rojo** |
| No se pudo alcanzar el sandbox (red, rate limit, repo o rama inexistente) | **rojo**, con ese motivo escrito y distinguido de una divergencia |

Que una entrada huérfana solo avise es deliberado: el sandbox es un repositorio que este no
controla, y lo que merece bloquear una PR es la diferencia **no explicada**, no la explicación que
sobra. Sin esa distinción, limpiar un residuo allá pondría en rojo la siguiente PR de acá, que no
tocó nada.

### Qué no cubre

- **El sensor no se espeja al sandbox.** Solo el monorepo tiene los dos lados de la comparación:
  allá correría con el árbol del monorepo vacío. Es la excepción a la simetría de
  `scripts/verificar-enlaces.mjs`, y queda escrita aquí y no en un mensaje de commit.
- **Modos de archivo distintos de `100644` y `100755`** (submódulos, symlinks) fallan explícitos en
  vez de compararse mal. Hoy los dos árboles son enteramente `100644`.
- **`.gitattributes` se compara primero**: tiene `* text=auto eol=lf`, así que una divergencia suya
  cambiaría los blobs de familias enteras de archivos de golpe, y el sensor lo dice en vez de
  escupir decenas de líneas que esconden la causa.

## Consecuencias

- **A favor**: la lista deja de depender de que alguien se acuerde de actualizarla — un archivo
  nuevo sin entrada rompe el job del PR que lo introdujo, que es el momento y la persona correctos.
  La razón de cada divergencia sigue junto al dato, sin segunda copia que se desincronice. Y la
  clasificación entre «divergencia por forma del repositorio» y «residuo» pasa a ser comprobable
  («existe en la raíz del monorepo») en vez de un juicio escrito a mano.
- **En contra**: el gate depende de un repositorio que este no controla y de una llamada de red. Se
  maneja con las severidades de arriba —solo la diferencia no explicada bloquea— y con que el fallo
  de red se reporte con su propio motivo. Además, el ADR gana una restricción de formato: las tres
  listas son ahora datos, y quien las edite tiene que respetar una ruta literal por entrada. Sigue
  viviendo solo en el monorepo, así que una sesión que trabaje únicamente en el sandbox no lo ve.
- **Qué haría reconsiderar**: que los dos repos dejen de mantenerse equivalentes —por ejemplo, si el
  sandbox se congela como material didáctico de una versión concreta—, o que el acoplamiento del
  gate al otro repositorio resulte insoportable en la práctica y haya que fijar un commit del
  sandbox en vez de leer su rama en vivo. Cualquiera de las dos sería un ADR nuevo que reemplace a
  este, no una excepción silenciosa.

**Referencias**: el [ADR-0002](0002-equivalencia-entre-el-monorepo-y-el-sandbox.md), al que
reemplaza; `instrumentacion-java-ia/sdlc-ia/skills/agent-context-java/references/monorepo-roots.md`;
el commit `ae096be` del sandbox (PR #41); los issues #141, #144 y #155;
[`AGENTS.md`](../../AGENTS.md) de la raíz, sección «Ramas y pull requests».
