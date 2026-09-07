# github-plan-build

## Qué es

Toma un issue de GitHub y lo lleva, con la mayor autonomía posible, hasta tener un **pull
request abierto, con CI en verde, los comentarios de revisión atendidos y el issue
actualizado**. Lee el issue y su discusión con la CLI `gh`, hace las preguntas de diseño que el
issue dejó abiertas, explora el repositorio, redacta un plan y lo somete a una revisión
adversarial desde tres ángulos distintos, pide aprobación explícita solo cuando el cambio lo
amerita, y después implementa con test primero, corre los propios gates del repositorio, abre el
pull request y lo acompaña hasta que quede verde.

No asume ninguna arquitectura ni stack en particular: un repositorio Java/Spring es un caso más
que sabe manejar, no una condición para funcionar.

## Cómo se invoca

```
/sdlc-ia:github-plan-build [número o URL del issue] [skip-checkpoint]
```

- El **número o URL del issue** es obligatorio (por ejemplo `42`, `#42`, o una URL completa de
  `github.com/.../issues/42`). Si no se indica, la skill lo pide.
- `skip-checkpoint` es opcional: le dice a la skill que para este issue en particular no haga
  falta pausar a pedir aprobación del plan, porque el usuario ya confía en que es un cambio de
  rutina. Nunca se salta el checkpoint si el usuario pidió explícitamente ver el plan.

## Resumen de las fases

1. **Resolver el acceso** — confirma que la CLI `gh` está autenticada y que el repositorio
   objetivo coincide con el remoto del checkout actual.
2. **Leer el issue y resolver el estado** — trae el issue completo (título, cuerpo, comentarios,
   etiquetas), porque muchas veces los requisitos reales están negociados en los comentarios, no
   en el cuerpo original. Como GitHub no tiene un campo de estado nativo, esta fase detecta si el
   repositorio usa **etiquetas** (labels) o **GitHub Projects v2** para marcar "en progreso" / "en
   revisión", y si encuentra ambos mecanismos, le pregunta al usuario cuál es la fuente de verdad
   en vez de escribir en los dos.
3. **Preparar el entorno de git** — sincroniza la rama por defecto, confirma que el árbol de
   trabajo está limpio (si no lo está, se detiene y avisa) y crea una rama
   `feature/<número>-<slug>` que siempre incluye el número del issue.
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
     si el cambio es chico y reversible, sigue directo.
   - **Implementar con test primero**, en pasos pequeños, corriendo el gate acotado después de
     cada uno.
   - **Correr los gates completos del repositorio** (lint, build, tests, `/code-review`, y
     `/security-review` si el cambio toca autenticación o entradas externas) antes de abrir el PR.
   - **Commit, push y apertura del PR**, enlazando el issue con el token de cierre automático de
     GitHub (`Closes #<n>`).
   - **Vigilar el CI hasta que quede verde** y atender los comentarios de revisión uno por uno.
   - **Cerrar** — publica el resumen final como comentario en el issue y actualiza su estado a
     "en revisión".

## Qué archivos toca o crea

Esta skill no genera plantillas propias: opera directamente sobre el código del repositorio
según lo que el plan aprobado indique — puede crear o modificar cualquier archivo de la rama de
trabajo. Además:

- Crea la rama `feature/<número>-<slug>`.
- Escribe comentarios y cambia etiquetas o el campo de estado del issue en GitHub (nunca en otro
  issue que no sea el que está trabajando).
- Abre el pull request correspondiente.

Nunca hace merge del PR, nunca activa auto-merge y nunca despliega a producción.

## Decisiones de diseño a tener en cuenta

- **El binding de STATUS depende de cómo el repositorio maneje el estado de sus issues.** GitHub
  no tiene un campo de estado nativo, así que la skill primero revisa si el repositorio usa
  **etiquetas** (`gh label list`, buscando convenciones como `in progress` o `in-review`) o
  **GitHub Projects v2** (un campo de selección única llamado `Status`). Si encuentra ambos
  mecanismos a la vez, pregunta una sola vez cuál es la fuente de verdad en vez de escribir en
  los dos; si no encuentra ninguno, salta los pasos de escritura de estado y lo deja anotado en
  el resumen, para que "se saltó" no se confunda con "se olvidó".
- **El único punto de pausa real es la aprobación del plan (paso E del ciclo), y es
  condicional.** Todo lo demás — explorar, implementar, corregir sus propios gates en rojo,
  escribir en el issue — se decide y se ejecuta sin pedir permiso.
- **Escala solo en casos concretos**: escrituras en producción o acciones destructivas,
  comunicaciones reales a clientes, un fallo de CI ambiguo (no se sabe si es intermitente o real),
  un ciclo de arreglos que no converge después de tres intentos sobre el mismo job, una decisión
  de producto sin respuesta clara, o falta de una credencial o permiso.
- **No asume ninguna arquitectura.** No revisa ni recomienda Clean Architecture, hexagonal, MVC
  ni ningún otro patrón con nombre propio: el plan sigue lo que el repositorio ya hace.
- **Los comandos de gate más comunes vienen preaprobados** (`make`, `npm`/`pnpm`/`yarn`,
  `pytest`, `go`, `cargo`, `dotnet`, `mvn`/`gradle`, `bundle`, `composer`, entre otros). Si el
  gate del repositorio no está en esa lista, la skill igual lo corre y acepta el permiso que
  aparezca — nunca se salta ni simula un gate para evitar el diálogo de confirmación.
