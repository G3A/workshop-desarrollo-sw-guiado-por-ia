# github-plan-build

## Qué es

Toma un issue de GitHub y lo lleva, con la mayor autonomía posible, hasta tener un **pull
request abierto, con CI en verde, los comentarios de revisión atendidos, el issue
actualizado y la lección de la vuelta enrutada**. Lee el issue y su discusión con la CLI `gh`,
hace las preguntas de diseño que el issue dejó abiertas, explora el repositorio, redacta un plan y
lo somete a una revisión adversarial desde tres ángulos distintos, pide aprobación explícita solo
cuando el cambio lo amerita, y después implementa con test primero, corre los propios gates del
repositorio, abre el pull request y lo acompaña hasta que quede verde.

El último paso —enrutar la lección— es el único que hace que la vuelta 20 sea distinta de la 1.
**Propone** a cuál de los cinco destinos va cada lección y con qué texto exacto; no escribe en
`AGENTS.md`, ni en el checklist de revisión, ni en un sensor. Aplicarlo es tuyo.

No asume ninguna arquitectura ni stack en particular: un repositorio Java/Spring es un caso más
que sabe manejar, no una condición para funcionar.

## Cómo se invoca

```
/sdlc-ia:github-plan-build [número o URL del issue] [skip-checkpoint] [confirm-push]
```

- El **número o URL del issue** es obligatorio (por ejemplo `42`, `#42`, o una URL completa de
  `github.com/.../issues/42`). Si no se indica, la skill lo pide.
- `skip-checkpoint` es opcional: le dice a la skill que para este issue en particular no haga
  falta pausar a pedir aprobación del plan, porque el usuario ya confía en que es un cambio de
  rutina. Nunca se salta el checkpoint si el usuario pidió explícitamente ver el plan.
- `confirm-push` es opcional y hace lo contrario: agrega una segunda pausa, después del commit y
  antes del push. La skill muestra la rama, los commits y los archivos que saldrían de tu máquina
  y pregunta una sola vez si sigue (push y PR) o si se detiene ahí para que el push lo hagas tú.
  Si eliges detenerte, no hay push, ni PR, ni comentario en el issue: te deja los comandos exactos
  para seguir a mano. Sin este argumento, el push no pide confirmación.

## Resumen de las fases

1. **Resolver el acceso** — confirma que la CLI `gh` está autenticada y que el repositorio
   objetivo coincide con el remoto del checkout actual.
2. **Leer el issue y resolver el estado** — trae el issue completo (título, cuerpo, comentarios,
   etiquetas), porque muchas veces los requisitos reales están negociados en los comentarios, no
   en el cuerpo original. Como GitHub no tiene un campo de estado nativo, esta fase detecta si el
   repositorio usa **etiquetas** (labels) o **GitHub Projects v2** para marcar "en progreso" / "en
   revisión", y si encuentra ambos mecanismos, le pregunta al usuario cuál es la fuente de verdad
   en vez de escribir en los dos.
3. **Preparar el entorno de git** — resuelve la rama de integración (la que declare `AGENTS.md`
   o `CLAUDE.md`; si no hay convención escrita, la rama por defecto del remoto), la sincroniza,
   confirma que el árbol de trabajo está limpio (si no lo está, se detiene y avisa) y crea desde
   ahí una rama `<prefijo>/<número>-<slug>` que siempre incluye el número del issue, con el
   prefijo que el repositorio ya usa (`feat/`, `fix/`, `docs/` en este monorepo; `feature/` solo
   si no hay convención). El PR se abre
   contra esa misma rama de integración; si no es la rama por defecto, la skill avisa que
   `Closes #<n>` no va a cerrar el issue al mergear esa PR, sino cuando la rama de integración
   se libere a la rama por defecto.
4. **Presentar el resumen del issue** — antes de tocar código, muestra título, estado,
   etiquetas, la rama creada, y marca cualquier issue relacionado que no esté cerrado, porque el
   estado y las etiquetas de un issue no son necesariamente confiables por sí solos.
5. **El ciclo de construcción** (definido en `references/build-loop.md`, compartido con las
   demás skills de "ticket a PR" del plugin) — en resumen:
   - **Preguntar** lo que el issue dejó abierto en materia de diseño, con opciones concretas.
   - **Explorar** el repositorio, incluso con subagentes en paralelo si el cambio toca varias
     áreas.
   - **Redactar un plan** paso a paso, con el primer paso siempre siendo una prueba que falla.
   - **Revisión adversarial** del plan desde tres ángulos (convenciones del repositorio,
     corrección, alcance) antes de escribir una sola línea de código.
   - **Punto de aprobación condicional** — solo entra en modo plan si el cambio es grande, toca
     un contrato público, un esquema de datos, permisos, o si la revisión dejó algo sin resolver;
     si el cambio es chico y reversible, sigue directo. Una vez aprobado, publica el plan como
     comentario en el issue (pasos, decisiones, supuestos y lo que queda fuera), para que el
     equipo lo lea sin abrir una sesión del agente.
   - **Implementar con test primero**, en pasos pequeños, corriendo el gate acotado después de
     cada uno y haciendo **un commit por paso en verde** con `Refs #<n>`; el commit que completa
     el último paso lleva `Closes #<n>`.
   - **Correr los gates completos del repositorio** (lint, build, tests, `/code-review`, y
     `/security-review` si el cambio toca autenticación o entradas externas) antes de abrir el PR.
   - **Commit, push y apertura del PR**, enlazando el issue con el token de cierre automático de
     GitHub (`Closes #<n>`). Cada commit cierra además con el trailer
     `Asistido-por-IA: <modelo>`, con el modelo que corrió la sesión, y el cuerpo del PR lo
     repite: así el repositorio puede separar los commits asistidos por IA del resto con
     `git log --format='%(trailers:key=Asistido-por-IA)'`, incluso después de un squash.
   - **Vigilar el CI hasta que quede verde** y atender los comentarios de revisión uno por uno.
   - **Cerrar** — publica el resumen final como comentario en el issue y actualiza su estado a
     "en revisión".
   - **Enrutar la lección** — tres preguntas sobre la vuelta que acaba de cerrar (qué costó más
     de lo previsto, qué tuviste que decir a mano que el agente debería haber sabido, qué atrapó
     un gate tarde o no atrapó ninguno) y, por cada lección, el destino y el texto exacto. «No
     hay ninguna» es una respuesta válida: inventar una para llenar el paso envenena los archivos
     a los que iría.

## Los seis destinos de una lección

| Si la lección es… | Va a… | Con qué forma |
|---|---|---|
| Una regla que el agente debe respetar al generar | `AGENTS.md` | Una línea corta |
| Un criterio que solo aplica al revisar | el checklist (`REVIEW.md`, si el repositorio lo tiene) | Una viñeta de qué mirar en el diff |
| El porqué de una decisión ya descartada | un ADR en `docs/adrs/` | Contexto, decisión, consecuencias |
| Algo que una máquina puede comprobar | deja de ser texto | una prueba, una regla de lint o un hook |
| Una preferencia tuya, no del equipo | `~/.claude/CLAUDE.md` | fuera del repositorio, a propósito |
| Todavía ninguna de las anteriores | `docs/lecciones.md`, la sala de espera | Una línea, **con la fecha en que entró** |

La cuarta fila es la que más rinde y la que menos se usa: **toda lección que una máquina pueda
comprobar debería terminar dejando de ser texto.** Un criterio escrito se olvida; un sensor no.

**La sala de espera lleva fecha, y la fecha es el mecanismo.** Cada entrada de `docs/lecciones.md`
termina promovida a uno de los cinco destinos o borrada; una entrada que lleva meses ahí no es una
lección pendiente, es una lección que no era. Sin fecha, el archivo se vuelve el vertedero que el
método dice que no debe ser.

## Qué archivos toca o crea

Esta skill no genera plantillas propias: opera directamente sobre el código del repositorio
según lo que el plan aprobado indique — puede crear o modificar cualquier archivo de la rama de
trabajo. Además:

- Crea la rama `<prefijo>/<número>-<slug>`, con el prefijo que el repositorio ya usa.
- Escribe comentarios y cambia etiquetas o el campo de estado del issue en GitHub (nunca en otro
  issue que no sea el que está trabajando).
- Abre el pull request correspondiente.

Nunca hace merge del PR, nunca activa auto-merge y nunca despliega a producción. Y **no escribe
`docs/lecciones.md`, `AGENTS.md`, `REVIEW.md` ni un ADR**: el último paso propone el texto, lo
pegas tú.

## Decisiones de diseño a tener en cuenta

- **El binding de STATUS depende de cómo el repositorio maneje el estado de sus issues.** GitHub
  no tiene un campo de estado nativo, así que la skill primero revisa si el repositorio usa
  **etiquetas** (`gh label list`, buscando convenciones como `in progress` o `in-review`) o
  **GitHub Projects v2** (un campo de selección única llamado `Status`). Si encuentra ambos
  mecanismos a la vez, pregunta una sola vez cuál es la fuente de verdad en vez de escribir en
  los dos; si no encuentra ninguno, salta los pasos de escritura de estado y lo deja anotado en
  el resumen, para que "se saltó" no se confunda con "se olvidó".
- **El único punto de pausa por defecto es la aprobación del plan (paso E del ciclo), y es
  condicional.** Todo lo demás — explorar, implementar, corregir sus propios gates en rojo,
  escribir en el issue, pushear — se decide y se ejecuta sin pedir permiso. La única pausa
  adicional es la del push, y solo si la pediste con `confirm-push`.
- **Escala solo en casos concretos**: escrituras en producción o acciones destructivas,
  comunicaciones reales a clientes, un fallo de CI ambiguo (no se sabe si es intermitente o real),
  un ciclo de arreglos que no converge después de tres intentos sobre el mismo job, una decisión
  de producto sin respuesta clara, o falta de una credencial o permiso.
- **El paso que enruta la lección propone y no aplica**, por dos razones y la segunda es
  mecánica. Un paso que edita el archivo que gobierna al agente cierra un bucle donde el agente
  escribe sus propias reglas y la vuelta siguiente las lee, sin que nadie haya mirado en el medio.
  Y para cuando llega ese paso, **el PR ya está abierto y en verde**: una regla nueva es sobre el
  *proceso*, no sobre la feature, cambia todas las vueltas siguientes, se revisa distinto y por
  eso va en su propio PR.
- **No propone inyectar `docs/lecciones.md` en el contexto del agente.** La sala de espera tiene
  que incomodar: una recuperación cómoda mata la presión de promover, que es el mismo argumento
  por el que el paquete no trae un servidor de memoria.
- **No asume ninguna arquitectura.** No revisa ni recomienda Clean Architecture, hexagonal, MVC
  ni ningún otro patrón con nombre propio: el plan sigue lo que el repositorio ya hace.
- **Los comandos de gate más comunes vienen preaprobados** (`make`, `npm`/`pnpm`/`yarn`,
  `pytest`, `go`, `cargo`, `dotnet`, `mvn`/`gradle`, `bundle`, `composer`, entre otros). Si el
  gate del repositorio no está en esa lista, la skill igual lo corre y acepta el permiso que
  aparezca — nunca se salta ni simula un gate para evitar el diálogo de confirmación.
