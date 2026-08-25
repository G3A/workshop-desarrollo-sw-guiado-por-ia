# F3 — Planificar

Issue real y `/sdlc-ia:github-plan-build` corriendo de verdad sobre `base-conocimiento/`, a partir
del hallazgo de ArchUnit que `agent-context-java` dejó documentado (sin arreglar, por diseño) en
F2: `allowEmptyShould(true)` obsoleto en `ArquitecturaTest` + paquete `seguridad` sin cobertura.

## 1. Issue real

[`#2`](https://github.com/G3A/workshop-desarrollo-sw-guiado-por-ia/issues/2) — creado con
`gh issue create`, con el hallazgo completo (líneas exactas, código citado, criterio de qué
revisar). No es un issue de ejemplo: viene de leer `ArquitecturaTest.java` y confirmar con `find`
que `web`/`teams` ya existen en el árbol.

## 2. `/sdlc-ia:github-plan-build 2`

**Fase 0-1 (acceso y lectura del issue):** `gh auth status` en verde, repo resuelto por el remoto
(`G3A/workshop-desarrollo-sw-guiado-por-ia`). Sin comentarios previos, sin labels, sin issues
relacionados.

**Resolución de `STATUS`:** `gh label list` solo tiene las labels default de GitHub (bug,
documentation, ...), ninguna de convención in-progress/in-review. `gh project list --owner G3A`
falló por falta del scope `read:project` en el token actual. Con ninguno de los dos mecanismos
confirmable, se decidió — tal como permite el propio skill — saltar los pasos de escritura de
`STATUS` y documentarlo acá en vez de forzar un `gh auth refresh` interactivo.

**Fase 2 (entorno git) — desviación real y deliberada:** el skill por defecto branchea desde la
rama por defecto del repo (`main`, confirmado con `gh repo view`). Pero `main` nunca se toca en
esta validación, y además `dev` todavía no tiene mergeada la instrumentación de F2 (PR #1 sigue
abierto) — branchear desde `dev` habría dejado la rama nueva sin `make ci`, sin el `Makefile`
instrumentado, sin nada para verificar. Se brancheó en cambio desde la punta de
`validacion/f3-planificar` (== `f2`), el mismo patrón que "apilar un segundo PR sobre un primero
sin mergear" en un flujo real. Rama resultante: `feature/2-archunit-seguridad-allowemptyshould`.

**Step A (grillar al usuario):** dos preguntas reales via `AskUserQuestion`, ninguna con respuesta
obvia en el issue:
1. Frontera de `seguridad` — ¿adaptador piel como `web`/`teams`, capa transversal más permisiva, o
   fuera de alcance? → **Igual que `web`/`teams`** (confirmado por evidencia de código: cero
   imports cruzados en cualquier dirección).
2. Profundidad de verificación — ¿alcanza con ArchUnit, o se agrega un romper-y-restaurar como en
   F2? → **Solo ArchUnit** (el comportamiento de `ApiTokenFilter` ya tiene su propio test).

**Step B (explorar):** lectura directa de `ArquitecturaTest.java`, los 3 archivos de `seguridad/`,
y grep de imports cruzados en todo `src/main/java` — sin violaciones reales encontradas antes de
tocar nada.

**Step C (borrador del plan)** y **Step D (revisión adversarial, 3 lentes en paralelo, agentes
`general-purpose` reales):**

| Lente | Hallazgo real que cambió el plan |
|---|---|
| Convenciones | `docs/architecture.md` y `AGENTS.md` quedarían desactualizados ("dos adaptadores" → son tres); el gate correcto antes de un PR es `make ci`, no `make check` (`make check` no corre `gitleaks`); falta el prefijo `sh` en `./mvnw` en Windows. |
| Corrección | Confirmado por grep que ninguna clase real viola las 3 reglas nuevas; `seguridad` es package-private, así que la mitad "núcleo no depende de seguridad" ya era cierta por el compilador; `modulosValidos` no es redundante con las reglas ArchUnit — no impone direccionalidad. |
| Alcance | `docs/java.md` documentaba ambos hallazgos como "estado real detectado" y mentiría si no se actualiza; `seguridad` es el único paquete de primer nivel sin `package-info.java` + `@ApplicationModule` — decisión de arquitectura real, no un supuesto menor. |

Las 3 lentes coincidieron, independientemente, en el hallazgo del `package-info.java` faltante —
señal fuerte. Se volvió a preguntar al usuario (segunda ronda, permitida por el skill: "a lo sumo
dos"): ¿cerrarlo en este mismo PR o dejarlo aparte? → **Cerrarlo acá** (resuelve también el TODO
explícito que ya tenía `docs/java.md`).

**Step E (checkpoint de aprobación):** el plan revisado terminó tocando 5 archivos
(`ArquitecturaTest.java` + 3 docs + 1 archivo nuevo) — cruza el umbral de "más de ~3 archivos" que
el propio skill define para entrar en modo plan. Se llamó `EnterPlanMode` de verdad, se escribió el
plan completo (contexto, decisiones, fuera de alcance, cambios archivo por archivo, verificación) y
se pidió aprobación real con `ExitPlanMode` — no simulada: la aprobación llegó como interacción real
del usuario en la sesión de Claude Code.

## 3. Lo que pasó después de la aprobación (real, no guionado)

La aprobación real del checkpoint dejó continuar el flujo tal como corre `github-plan-build` de
verdad: Steps F (implementar) → G (gates) → H (commit/push/PR) → I (CI) → J (wrap-up) ocurrieron en
la misma sesión, sobre `feature/2-archunit-seguridad-allowemptyshould` — la "propia rama `feature/…`
del skill" que anticipaba el plan original de esta validación. Evidencia completa (commits, gate
local, PR real, CI en verde) en `validacion-workshop/f4-f5-implementar-y-verificar.md`.

## Hallazgo de entorno (no del código)

`gh` y `gitleaks`, instalados vía `winget` en esta misma máquina, no estaban en el `PATH` de la
sesión de shell de Claude Code (Git Bash) — hubo que invocarlos por ruta completa. No es un fallo
del skill ni del repo; es una particularidad de cómo `winget` actualiza el `PATH` de usuario y de
cuándo una sesión de shell ya abierta lo recoge.
