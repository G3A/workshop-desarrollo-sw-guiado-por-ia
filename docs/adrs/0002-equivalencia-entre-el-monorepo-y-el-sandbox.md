# ADR-0002: Qué se espeja entre el monorepo y `base-conocimiento-sandbox`, y qué difiere a propósito

## Estado

Aceptado — 2026-09-16.

## Contexto

`base-conocimiento` vive en dos repositorios que se mantienen **equivalentes a propósito**:

- `G3A/workshop-desarrollo-sw-guiado-por-ia`, en el subdirectorio `base-conocimiento/`;
- `G3A/base-conocimiento-sandbox`, standalone, que es el repositorio de los manuales del workshop.

La convención es que todo cambio en uno va también al otro, y que **lo que difiere, difiere a
propósito y anotado**. Hasta ahora esa segunda mitad no tenía dónde vivir: la lista de qué difiere
existía repartida entre mensajes de commit, el `README.md` de cada repo y la memoria de quien
hiciera el espejo. El issue #141 lo destapó con un caso concreto.

Dos hechos que enmarcan la decisión:

- **La omisión de #141 fue deliberada y estaba escrita, pero en un sitio que nadie vuelve a leer.**
  El commit de squash de la PR #41 del sandbox (`ae096be`) dice: «REVIEW.md, la plantilla de PR y
  EXPERIMENTS.md quedan fuera: en el monorepo viven en su raiz y aplican a las cuatro piezas». El
  razonamiento era correcto para el monorepo y equivocado para el sandbox, y hizo falta abrir un
  issue aparte para volver sobre él: un mensaje de commit no se consulta, se escribe una vez y se
  entierra. Esta vez se revisó el mismo día; el problema no es cuánto tardó, sino que el registro
  depende de que alguien recuerde que existe.
- **La regla que decide dónde va cada archivo ya estaba escrita**, en
  `instrumentacion-java-ia/sdlc-ia/skills/agent-context-java/references/monorepo-roots.md`: cada
  archivo se ancla a la raíz de **quien lo lee**. `REVIEW.md`, la plantilla de PR y `EXPERIMENTS.md`
  los leen el servicio de Code Review, GitHub y el equipo, los tres desde la raíz del **repositorio**;
  `AGENTS.md`, `CLAUDE.md`, `docs/` y `COMPONENTS.md` los lee el agente desde la raíz del
  **proyecto**. En el monorepo esas dos raíces son distintas; en el sandbox son la misma. El mismo
  proyecto, en dos repos con forma distinta, produce dos árboles legítimamente distintos.

Opciones evaluadas para registrar las divergencias:

1. **Seguir en los mensajes de commit**, como hizo #41. Cuesta cero y es lo que ya pasó: el registro
   existe pero nadie lo encuentra, y la decisión se revisa cuando alguien abre un issue por otra
   razón.
2. **El `docs/claims-ledger.md` de cada repo.** Es donde ya se registra qué afirma cada documento y
   si sigue vigente, y de hecho ahí van las afirmaciones sobre dónde vive cada archivo. Pero el
   ledger contesta «¿esto sigue siendo cierto?», no «¿por qué los dos repos difieren?»; y al haber
   uno por repo, la lista de divergencias quedaría duplicada en los dos lados, que es justo la clase
   de cosa que se desincroniza.
3. **Una sección en el `README.md` del monorepo**, junto a la nota que ya existe sobre el esquema de
   ramas distinto del repo hermano. Más visible, pero mezcla una decisión con la documentación de
   lectura, y el `README.md` es uno de los archivos que difieren: la sección no se espejaría.
4. **Una sección en el `AGENTS.md` del sandbox**, que lo lee el agente que trabaja ahí. Deja el
   registro de un solo lado, y `AGENTS.md` también difiere entre los dos repos.
5. **Un ADR en `docs/adrs/` de la raíz del monorepo** — este archivo.

## Decisión

Opción 5. **La relación espejo y su lista de divergencias se registran en un ADR de la raíz del
monorepo.** Es donde este repositorio ya guarda las decisiones que no son de una sola pieza, y un
ADR se escribe una vez, se cita por ruta y se reemplaza por otro ADR cuando cambia — que es
exactamente el ciclo de vida que le faltaba a esta información.

El `claims-ledger` de cada repo sigue siendo el sitio de las afirmaciones puntuales («`REVIEW.md`
vive en la raíz del repositorio git»), y este ADR el de la relación entre los dos árboles. No se
duplican: el ledger dice qué es cierto hoy, el ADR dice por qué los dos repos no son idénticos.

### Qué se espeja

Todo `base-conocimiento/` del monorepo ↔ la raíz del sandbox. La verificación es por hashes de
blob, no por fechas ni por confianza:

```
git ls-tree -r origin/dev -- base-conocimiento | sed 's|base-conocimiento/||'   # en el monorepo
git ls-tree -r origin/dev                                                       # en el sandbox
```

El `sed` no es adorno: sin él, el listado del monorepo prefija cada ruta con `base-conocimiento/` y
la comparación marca como distintos el 100 % de los archivos.

**El clon local puede estar atrasado.** Por eso la receta dice `origin/dev` y no `dev`, y por eso va
precedida de un `git fetch`; para una pregunta puntual —«¿el sandbox tiene este archivo?»— es más
directo preguntarle al remoto:
`gh api repos/G3A/base-conocimiento-sandbox/contents/<ruta>?ref=dev`. Un `grep` sobre el árbol local
responde por la copia que uno tiene, no por el repositorio: en #144 ese clon estaba 15 PR atrás y
dio un falso negativo —«el sandbox no tiene este sensor»— que estuvo a punto de entrar a un plan
como un hecho, y con él una divergencia que nadie habría anotado.

### Qué difiere a propósito

**Difieren por contenido (9 archivos), y no deben sincronizarse:**

| Archivo | Por qué |
|---|---|
| `AGENTS.md`, `README.md` | Estructura monorepo vs. standalone. El del sandbox lleva además el aviso de que su `main` es el snapshot «antes de instrumentar con IA» y no se mergea. |
| `docs/claims-ledger.md` | Referencia números de issue que no coinciden entre repos. |
| `.gitignore`, `.gitleaks.toml`, `.gitleaksignore` | Huellas y rutas propias de cada repo. |
| `docs/infrastructure.md`, `docs/java.md`, `docs/adrs/0012-*.md` | Mismo texto con las rutas del monorepo reescritas para el standalone, y el conteo de hooks y la ubicación del CI, que difieren de verdad. |

**Existen solo en el sandbox, por la forma del repositorio (15 archivos):**
`.claude/settings.json`, `.mcp.json`, `lefthook.yml`, `.github/workflows/ci.yml`, los siete
`scripts/agent-hooks/*.sh`, —desde #141— `REVIEW.md`, `.github/pull_request_template.md` y
`EXPERIMENTS.md`, y —desde #144— `scripts/verificar-enlaces.mjs`. En el monorepo sus equivalentes viven en la raíz del repositorio, una carpeta más
arriba de `base-conocimiento/`, así que la comparación de árboles los ve como ausentes sin que falte
nada. **`REVIEW.md`, `.github/pull_request_template.md` y `EXPERIMENTS.md` son el caso que más
confunde**: existen en los dos repos, con el mismo contenido salvo lo que se dice abajo, y aun así
la receta los lista como «solo en el sandbox».
Comprobarlos exige comparar contra la raíz del monorepo, no contra `base-conocimiento/`.

**`scripts/verificar-enlaces.mjs`, desde #144:** hasta ese issue vivía en
`base-conocimiento/scripts/` y se espejaba con el mismo blob. Al pasar a cubrir los `.md` de todo el
monorepo se mudó a la raíz, así que la receta lo lista como «solo en el sandbox» por la misma razón
que a los tres anteriores. La simetría que vale recordar: en los **dos** repos el sensor está en
`scripts/verificar-enlaces.mjs` respecto de su propia raíz, de modo que el mismo contenido sirve tal
cual en ambos. **Espejado con el PR #44 del sandbox:** los dos tienen el mismo cuerpo de script.
Difieren solo en la cabecera de comentarios, y es deliberado — la del sandbox nombra los issues de
este repositorio como espejo y no habla del plugin, que allá no existe —, así que es una divergencia
de la misma clase que la de `docs/java.md`. Ojo con la trampa: como el archivo ya no está bajo
`base-conocimiento/`, **la receta de comparación de arriba no lo alcanza**; comprobarlo es
`git rev-parse dev:scripts/verificar-enlaces.mjs` en cada repo.

**Los tres archivos de la raíz, desde #141:** `REVIEW.md`, `.github/pull_request_template.md` y
`EXPERIMENTS.md` pasan a estar en los dos repos —en el sandbox con la PR #43, que es la que espeja
este issue—, con estas diferencias deliberadas:

- el `REVIEW.md` del sandbox no lleva el bloque condicional del preámbulo ni el prefijo
  `base-conocimiento/` en sus ítems, y no menciona el plugin ni `playbook-sdlc-ia/vendor/`, que allá
  no existen;
- la plantilla de PR difiere en dos puntos: su comentario final, porque el sandbox mergea por squash
  con el mensaje **por defecto** y el monorepo con el cuerpo de la PR; y el enlace a `REVIEW.md`,
  que es una URL absoluta al propio repositorio y por construcción nunca puede ser idéntica. La URL
  absoluta no es capricho: una ruta relativa **404** en el cuerpo renderizado de una PR, que es el
  único sitio donde alguien hace clic en ese enlace. Desde el issue #146 la plantilla que genera
  `agent-context-java` ya emite la URL absoluta, así que volver a correr la skill sobre cualquiera de
  los dos repos conserva esta divergencia en vez de reintroducir el enlace roto;
- el `EXPERIMENTS.md` del sandbox cubre un solo proyecto y cita la skill por URL, no por ruta, y no
  lleva el comentario de procedencia que el del monorepo sí tiene.

Los **cuatro límites del permiso** de su sección 2 son los mismos en los dos, palabra por palabra:
son del equipo, no del repositorio.

### Qué no es divergencia, sino pendiente

Ocho archivos de salida sueltos en `scripts/` del sandbox sin contraparte en el monorepo:
`error-api.txt`, `kb-ollama-select-string.md`, `ollama-ps.txt`, `resultado-diagnostico.md`,
`resultado-make-check`, `resultados-make-up.md`, `resultados-make-verificar.md` y `revisar.txt`. No
son una diferencia decidida: son residuos de corridas, de la misma clase que el `scripts/.env` que
la PR #39 del sandbox ya sacó. **Quedan anotados aquí como pendientes de limpieza**, no como
divergencia legítima, para que la próxima comparación de árboles no los lea como acordados.

## Consecuencias

- **A favor**: la comparación por hashes pasa a tener una respuesta escrita para cada diferencia,
  así que un archivo nuevo en la lista es una señal y no ruido; la razón de cada divergencia queda
  junto a la regla que la produce (`monorepo-roots.md`), no repartida en mensajes de commit; y los
  residuos quedan separados de lo deliberado, que era la parte imposible de distinguir a simple
  vista.
- **En contra**: el ADR vive solo en el monorepo —`docs/adrs/` de la raíz no se espeja— así que una
  sesión que trabaje únicamente en el sandbox no lo ve. Se maneja con la fila del `claims-ledger`
  del sandbox, que sí apunta a la regla, y con que todo espejo se hace desde el monorepo. Además,
  esta lista envejece: hay que actualizarla en el mismo PR que cree o cierre una divergencia, y
  nada lo verifica a máquina — el sensor que lo haría está propuesto en #155.
- **Qué haría reconsiderar**: que los dos repos dejen de mantenerse equivalentes —por ejemplo, si el
  sandbox se congela como material didáctico de una versión concreta— o que aparezca un sensor que
  compare los dos árboles en CI. Lo segundo es lo que convertiría esta lista en una regla
  verificable en vez de un acuerdo escrito, y sería un ADR nuevo que reemplace a este, no una
  excepción silenciosa.

**Referencias**: `instrumentacion-java-ia/sdlc-ia/skills/agent-context-java/references/monorepo-roots.md`;
el commit `ae096be` del sandbox (PR #41); el issue #141 y la PR #43 del sandbox que lo espeja;
`AGENTS.md` de la raíz, sección «Ramas y pull requests».
