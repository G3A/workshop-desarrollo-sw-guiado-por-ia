# Siete miradas, un issue, un acta

### Una mesa redonda elige un issue y lo lleva de punta a punta con `sdlc-ia`

<small>G3A/base-conocimiento-sandbox · issue #52 · PR #53 · 2026-10-26</small>

Note:
Les voy a contar algo que hice hoy, no algo que planeé hacer. Reuní una mesa de siete perfiles,
eligieron un issue, lo llevé de punta a punta en un repositorio real con el plugin sdlc-ia, y al
final revisé el acta que dejó la IA. Todo lo que van a ver tiene un enlace: issue, PR, commits y
acta. Y también les voy a mostrar lo que no salió como esperaba, porque eso es lo que más me
enseñó.

---

## El reto que me puse

1. **Un issue que valga la pena**, elegido entre varios perfiles, no por mí solo.
2. **De punta a punta con `/sdlc-ia:github-plan-build`**: plan, rojo, verde, PR y CI.
3. **Con el acta de la IA completa** en la PR, como pide el ADR-0006: cruda, curada, pack de
   objetos, índice y el trailer `Registro-IA:` en cada commit.

<small>Repositorio: `G3A/base-conocimiento-sandbox`, un RAG en Java/Spring que espeja
`base-conocimiento/` del monorepo.</small>

Note:
Me puse tres condiciones. La primera: que el issue no lo eligiera yo solo, porque uno siempre
elige lo que ya sabe hacer. La segunda: que lo hiciera la skill github-plan-build, sin que yo
escribiera código. La tercera es la que más me importa: que al final quedara un acta de la IA
verificable, versionada en git junto al código, como dice el ADR-0006 del taller.

---

## La mesa: siete perfiles, siete propuestas

| | Perfil | Propuesta |
|---|---|---|
| A | UX | Los errores de la consola de admin se ven y se anuncian |
| B | UI | El tema claro cumple contraste WCAG AA, con un sensor JUnit |
| C | Arquitectura | El grafo de módulos de Spring Modulith como contrato |
| D | Calidad | Un sensor de trazabilidad: `Refs` y `Registro-IA` en cada commit |
| E | Full stack | Un panel de «respuestas que no sirvieron» |
| F | IA (PhD, MIT) | Las citas `[n]` fuera de rango se marcan como advertencia |
| G | Líder de QA | Pruebas de contrato del verificador de grounding |

<small>Cada perfil fue un agente con su rol, que leyó el repo por separado. Registro completo:
`mesa-redonda.md`.</small>

Note:
Cada perfil fue un subagente de Claude con su rol: UX, UI, arquitectura, calidad, full stack,
liderazgo de QA y un PhD en IA. Leyeron el repositorio por separado y cada uno trajo un problema
real, con archivo y línea. Ninguna propuesta era mala. La de UX, por ejemplo, encontró un error que
la consola se traga en silencio. Pero teníamos que elegir una sola.

---

## El voto: ganó F, cinco de siete

Antes de votar puse sobre la mesa cuatro restricciones que nadie había pesado:

- **Espejo**: cada archivo que cambie se copia byte a byte al monorepo.
- **El CI no corre Playwright**: una prueba de navegador no es un gate.
- **El acta** va en la PR.
- **Menos de diez minutos** para contarlo.

UX y Calidad **retiraron su propia propuesta**. F absorbió de G el caso de `content` nulo, y de
todos, una condición: **el rojo se commitea antes del verde**.

Note:
Lo interesante fue el segundo turno. Cuando agregué las restricciones reales, UX y Calidad votaron
contra su propia propuesta: la de UX dependía de Playwright, que el CI no corre, y la de Calidad
tocaba el workflow, que también hay que espejar. Ganó la del PhD en IA, cinco votos de siete. Y la
mesa le puso una condición que después voy a revisar en el acta: que la prueba en rojo quede
commiteada antes del arreglo.

---

## El issue: una cita inventada pasaba sin marca

**Antes**: el modelo recibe 2 fragmentos y responde `"X [9]"`. Nadie lo nota.

**Después**: la respuesta trae una advertencia, sin una segunda llamada al LLM.

```java
List<String> advertencias =
    pre.respuestaFija() != null
        ? List.of()
        : ValidadorDeCitas.advertencias(
            ValidadorDeCitas.validar(textoRespuesta, pre.citas().size()));
```

> «La cita [9] no corresponde a ninguno de los fragmentos consultados.»

<small>[sandbox#52](https://github.com/G3A/base-conocimiento-sandbox/issues/52) · la tarjeta de
Teams ya mostraba `advertencias` con ⚠</small>

Note:
El problema es simple. El orquestador numera el contexto del uno al k, pero si el modelo cita un
número que no existe, la respuesta sale igual. Es la alucinación más barata de detectar, y no se
detectaba. El arreglo es determinista: Java revisa la forma de las citas y el LLM sigue escribiendo
el texto. Lo bonito es que la tarjeta de Teams ya pintaba las advertencias, así que el cambio se ve
sin tocar el front.

---

## El recorrido, paso a paso del proceso

| Paso del visor | Qué pasó | Evidencia |
|---|---|---|
| Planificar el issue | Plan con 3 revisiones adversariales, 14 hallazgos | `docs/plans/plan-issue-52-…` |
| Crear rama antes de implementar | `feat/52-validador-de-citas`, desde `dev` | commit `0fbe988` |
| Ejecutar tareas, 1 commit c/u | rojo → arreglo → corrección de review | 3 commits + acta |
| Verificación en 4 capas | `make check`: 227 pruebas, 0 fallas | acción a72 del acta |
| Revisión de código | `/code-review`: 8 hallazgos, 2 corregidos | commit `16b8be9` |
| Abrir Pull Request | `Registro-IA:` repetido en el cuerpo | [PR #53](https://github.com/G3A/base-conocimiento-sandbox/pull/53) |
| Merge (Rulesets exige checks) | CI `check` verde en 2m8s; squash | `3b4aaac` en `dev` |

Note:
Así se ve contra el visor del proceso operacional. Cada fila es un nodo del ciclo por issue, y la
columna de la derecha es dónde lo pueden comprobar. El plan pasó por tres revisiones adversariales
que encontraron catorce problemas, por ejemplo que un fragmento de código Java con corchetes no
debía contarse como cita. Las pruebas pasaron, la revisión de código encontró ocho cosas y se
corrigieron dos; las otras seis quedaron argumentadas en la PR. El merge lo hice yo.

---

## Rojo, después verde

```text
0fbe988  test: la cita [9] con 2 fragmentos no genera advertencia (rojo)
70ef754  feat: las citas [n] fuera de rango se marcan como advertencia
16b8be9  fix: el validador ignora también bloques sin cerrar y con ~~~
4569c79  chore: acta de la sesión del issue #52
```

El rojo falló **por la razón correcta**:

```text
Exit code 1
  ["La cita [9] no corresponde a ninguno de los fragmentos consultados."]
```

Note:
Esta es la condición que puso la mesa. El primer commit es solo la prueba y falla. Y falla por la
razón correcta: espera exactamente el texto de la advertencia, y la advertencia no está. Solo
después viene el arreglo. Si el orden fuera al revés, nadie podría saber si la prueba muerde. El
cuarto commit es el acta, que les muestro en un momento.

---

## Lo humano lo hice yo, y quedó registrado

1. **Arranque sin persona**: `claude -p "/sdlc-ia:github-plan-build 52"`, en un clon aparte.
2. La sesión **se detuvo en el paso F**: nadie podía aprobar `make` ni `mvnw`.
3. La retomé con `claude --resume <id> --permission-mode default`: **mismo id, una sola acta**.
4. Aprobé el plan y **22 permisos**. El merge lo corrí yo.

<small>Si yo hubiera aprobado desde `-p`, el acta registraría una aprobación humana que no
ocurrió.</small>

Note:
Una decisión de diseño importante. Arranqué la sesión sin persona, y se detuvo justo donde tenía
que detenerse: cuando hubo que correr comandos. Ahí la retomé yo, con el mismo identificador de
sesión, para que todo quedara en una sola acta. Todo lo que aprobé quedó registrado como
intervención mía: el plan y veintidós permisos. Por eso el merge lo corro yo y no el agente.

---

## El acta, contra el ADR-0006

| Requisito | Resultado |
|---|---|
| Cruda, curada, pack de objetos e índice en la PR | ✅ commit `4569c79` |
| `Registro-IA:` en cada commit asistido | ✅ 4 de 4, y en el cuerpo de la PR |
| La curada sale de su cruda; el diff sale de árboles del acta | ✅ «1 actas citadas, todas llegan» |
| El motor reproduce las acciones sin el modelo | ✅ 71 de 117; 8 comandos divergen por entorno |
| Nada personal publicado | ✅ ni cuenta, ni dominio, ni email |

```text
node $ACTA/verificar-registro-ia.mjs --rango origin/dev..HEAD --cuerpo-pr cuerpo.md
```

Note:
Esta es la parte que más quería mostrar. Corrí el verificador del ADR-0006 sobre el rango de la
PR. Las cuatro piezas están, cada commit cita su acta, la curada sale de su cruda, y un motor en
un contenedor reprodujo setenta y una acciones sin el modelo. Ocho comandos divergen porque el
contenedor no tiene Maven ni red, y el ADR dice que eso avisa y no bloquea. Revisé además que el
acta no publicara nada mío.

---

## Lo que el acta me dijo, aunque no me gustara

- **Dos fallas de la propia acta** antes de poder curarla:
  - Claude Code 2.1.310 dice «requires **manual** approval» y el patrón no lo reconocía.
  - Una sesión retomada tomaba el `SessionEnd` intermedio como final.
- **El rojo no está en la curada**: el comando no llevó `# rojo-esperado` y se descartó.
- **El checkpoint del plan se saltó** en `-p`: el código empezó antes de mi aprobación.
- **La conformidad marca 9 de 16 pasos sin marcador**, aunque el trabajo se hizo.
- **El espejo se habría puesto en rojo** con el acta del sandbox.

Note:
Y ahora lo incómodo, que es lo valioso. El acta no se podía curar: encontré dos fallas en su
compilador, las dos destapadas por esta sesión real, y ya las arreglé con sus pruebas. El rojo que
pidió la mesa existe en el historial, pero no en la curada, porque el comando no llevaba la marca
de rojo esperado. El plan se aprobó tarde. Y el sensor de espejo del monorepo habría fallado con
cinco archivos del acta. Sin acta, nada de esto se habría visto.

---

## Antes y después: del curso a hoy

| Tema | Lo que vio el curso | Lo que se actualizó (origen en Arkandia) |
|---|---|---|
| Contexto del agente | `AGENTS.md` a mano | `agent-context-java` valida cada afirmación |
| Especificación | SDD con Spec Kit u OpenSpec | `requirement-to-spec-java`: requisito → spec y tareas |
| Ticket → PR | Backend en demo en vivo | `github-plan-build`, pasos A–K y sub-issues |
| Controles deterministas | CI con linters y pruebas | Cada control probado rompiéndolo primero |
| Hooks | Un `PostToolUse` que formatea | Catálogo de hooks de seguridad con regresión |
| Cadena de suministro | `audit` de dependencias | gitleaks fijado con checksum verificado |

<small>Curso: resumen de las 5 sesiones de mayo de 2026. Arkandia:
[ArkandiaLabs/arkandia-skills](https://github.com/ArkandiaLabs/arkandia-skills), `CHANGELOG.md`
v0.1.0 → v0.5.0.</small>

Note:
Hagamos zoom hacia atrás. En mayo, cuando terminó el curso, Arkandia tenía una sola skill. Lo que
vimos en clase lo hacíamos en demo en vivo. Hoy esas prácticas son skills que se repiten igual cada
vez, y ningún control se da por bueno sin romperlo primero. Una aclaración honesta: el patrón de
pasos y sub-issues sí viene de Arkandia; github-plan-build es una skill propia del taller. Y el acta
de la IA que acaban de ver no viene de Arkandia: la construimos aquí.

---

## La retrospectiva: dónde va cada lección

| Lección | Destino | Estado |
|---|---|---|
| El curador no reconocía «manual approval» ni una sesión retomada | Código del acta + pruebas | ✅ hecho en #253 |
| El sensor de espejo no distinguía el acta | Sensor + ADR-0003 | ✅ hecho, con su rojo |
| La redacción dejaba pasar mi identidad escrita como regex | Código del acta + pruebas | ✅ hecho en #253 |
| El rojo de TDD debe llevar `# rojo-esperado` | Regla de la skill | Propuesta |
| En `-p` la skill debe detenerse en el plan | Paso E de `github-plan-build` | Propuesta |
| El CI del sandbox no verifica el acta | Sensor en `ci.yml` | Propuesta |

Note:
El último paso de la skill no aplica lecciones: las propone y dice dónde van. Tres ya están
aplicadas en la PR del número doscientos cincuenta y tres, cada una con su prueba en rojo primero.
La tercera me tocó de cerca: al registrar el acta de esta charla, mi propio comando de revisión
iba a publicar mi email. Las otras tres quedan como propuestas: que el rojo de TDD lleve su
marca, que la skill no se salte el plan cuando no hay persona, y que el CI del sandbox verifique
el acta como ya lo hace el del monorepo.

---

## Lo que me llevo

- **Varias miradas eligen mejor** que una, sobre todo cuando cambian de opinión con datos.
- **La IA trabajó y yo decidí**: plan, permisos y merge.
- **El acta no es burocracia**: destapó media docena de problemas reales en una sola sesión.

<small>Todo es reproducible: [issue #52](https://github.com/G3A/base-conocimiento-sandbox/issues/52)
· [PR #53](https://github.com/G3A/base-conocimiento-sandbox/pull/53) · acta en
`.ia/registros/52/`</small>

Note:
Me llevo tres cosas. Que siete miradas eligieron mejor que yo solo, y lo mejor fue verlas cambiar
de opinión con datos. Que la IA hizo el trabajo y yo tomé las decisiones, y que eso quedó escrito.
Y que el acta no es burocracia: en una sola sesión destapó media docena de problemas que
ninguna prueba sintética había visto. Gracias. Todo lo que mostré está enlazado para que lo revisen.
