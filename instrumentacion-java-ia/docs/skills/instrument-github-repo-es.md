# instrument-github-repo

## Qué es

Escribe el **Ruleset de GitHub** que convierte los checks de CI en un juez de verdad.

`instrument-project-java` instala trece controles, los prueba rompiendo uno por uno, y escribe
`.github/workflows/ci.yml`. Y ahí termina. **Un workflow que corre no bloquea nada**: lo que impide
el merge es el Ruleset que exige ese workflow. Sin él, un repositorio queda con todos los sensores y
ningún juez — los checks informan y el merge pasa igual.

Es una skill aparte y no un paso más de la otra porque cambia **la configuración del remoto a
través de la API de GitHub**, no archivos del árbol de trabajo: otros permisos, otra forma de
fallar, y sin un `git checkout` que deshaga nada.

## Cómo se invoca

```
/sdlc-ia:instrument-github-repo
```

No recibe argumentos.

## Qué escribe

| Artefacto | Qué cambia |
|---|---|
| Ruleset en la rama de integración | No hay merge sin el check en verde y una revisión |
| Ruleset en la rama de liberación, solo si el repositorio tiene una | Lo mismo, con el método de merge que las liberaciones necesitan |

## Fases principales

1. **Descubrimiento silencioso** — resuelve la topología de ramas leyendo `AGENTS.md` antes que
   ningún default, lista los Rulesets que ya existen, mide el tamaño del equipo, y sobre todo
   averigua **el nombre real del check**: es el nombre del *job* tal como aparece en una corrida
   terminada, no el del archivo del workflow ni el de su `name:`. Si el workflow nunca corrió, se
   detiene y lo dice.
2. **Prerrequisitos** — `gh` autenticado, permiso de administrador sobre el repositorio (los
   Rulesets son un endpoint solo para administradores) y al menos una corrida terminada del
   workflow. Reporta lo que falte y se detiene; no hace una instalación a medias.
3. **Acordar el alcance** — pregunta lo que el descubrimiento no puede resolver, diciendo la
   consecuencia y no solo las opciones: qué ramas proteger, cuántas aprobaciones, qué método de
   merge por rama, si hace falta un bypass y con qué nivel de aplicación.
4. **Aplicar** — confirma la intención antes de la primera escritura, porque es la única skill del
   paquete cuyos efectos viven fuera del árbol de trabajo. Si ya existe un Ruleset para esa rama
   **no escribe**: lo trae, lo compara campo por campo y reporta una tabla de tres columnas — qué
   tiene, qué escribiría, qué cambiaría eso.
5. **Verificar bloqueando** — abre una PR desechable contra la rama protegida y confirma que
   GitHub la reporta como `BLOCKED`, y por el motivo que se configuró. Después la cierra.
6. **Documentar y reportar** — actualiza `AGENTS.md` si existe, y reporta cada Ruleset con sus
   reglas, el nombre del check y de qué corrida salió, la evidencia del bloqueo, y cada bypass con
   su motivo y su condición de retirada.

## Las tres formas de tener un Ruleset que no protege nada

Son la razón de que la fase 5 exista, y las tres devuelven `201`:

1. **`conditions.ref_name.include` con el nombre pelado de la rama** en vez del ref completo
   (`refs/heads/dev`). No coincide con ninguna rama, y el Ruleset se ve instalado.
2. **`enforcement` en `evaluate`** — registra, nunca bloquea. Es una opción legítima para un equipo
   que quiere observarlo un sprint, pero no es un juez, y el reporte tiene que decirlo.
3. **Un `context` que ninguna corrida produce** — bloquea todo para siempre, esperando un check que
   no llega. Desde fuera se ve igual que un juez funcionando, durante la primera hora.

Solo un intento de merge las distingue.

## La asimetría entre las dos ramas

Los dos Rulesets difieren en exactamente dos campos, y los dos son deliberados:

| | Rama de integración | Rama de liberación |
|---|---|---|
| `allowed_merge_methods` | `["squash"]` | `["merge"]` |
| `strict_required_status_checks_policy` | `true` | **`false`** |

El `false` parece un descuido y no lo es. `strict` significa «la rama tiene que estar al día con su
base antes de mergear». Como la integración entra por squash, la rama de integración **nunca**
contiene los merge commits de las liberaciones anteriores, así que está permanentemente «behind».
Con `true` ahí, toda liberación queda bloqueada como desactualizada.

Copiar la misma plantilla a las dos ramas es justo el fallo que la tabla existe para evitar.

## Decisiones de diseño a tener en cuenta

- **Nunca sobrescribe un Ruleset existente.** Puede llevar protecciones que alguien puso a mano.
  Reporta las diferencias y deja decidir a una persona — el mismo «fusiona, nunca reemplaza» que el
  resto del paquete aplica al `Makefile`, a `.mcp.json` y a `settings.json`. Actualizar es
  `--method PUT` sobre el id existente, y es decisión de una persona, nunca de la skill.
- **Nunca borra un Ruleset.** Ni al fallar, ni al limpiar, ni para que la verificación pase.
- **Todo bypass se explica.** Un bypass es un agujero en el juez. Hay una razón honesta para
  abrirlo: un repositorio de una sola persona no puede cumplir «una aprobación», porque nadie puede
  aprobar su propia PR. Se escribe después de decirlo en voz alta, y con la condición de retirada
  anotada en `AGENTS.md`.
- **Un juez que bloquea todo está tan roto como uno que no bloquea nada**, y es el que se descubre
  más tarde y más caro. Por eso la fase 5 comprueba también que deje pasar lo que debe.
- **Fuera de alcance**: `CODEOWNERS`, los Environments de despliegue y los Rulesets a nivel de
  organización. Se nombran como lo que queda manual.
