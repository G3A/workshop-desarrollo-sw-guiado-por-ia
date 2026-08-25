# F4 — Implementar / F5 — Verificar + PR + CI

Continuación real de `/sdlc-ia:github-plan-build` sobre el issue
[`#2`](https://github.com/G3A/workshop-desarrollo-sw-guiado-por-ia/issues/2), tras la aprobación
real del checkpoint documentado en `validacion-workshop/f3-planificar.md`. Todo ocurrió, como
anticipaba el plan original de esta validación, sobre la misma rama:
`feature/2-archunit-seguridad-allowemptyshould` (nace de la punta de `f3`/`f2`).

## Step F — Implementar

Cambios reales en 5 archivos, sin tocar ninguna clase de producción:

1. `ArquitecturaTest.java`: quitado `.allowEmptyShould(true)` de las 3 reglas `noClasses()`;
   `seguridad` sumado como tercer adaptador piel en las 3 reglas; comentario obsoleto de la línea
   39 reescrito.
2. `seguridad/package-info.java` (nuevo): `@ApplicationModule(displayName = "Seguridad")` +
   Javadoc, mismo patrón que `web`/`teams`.
3. `docs/architecture.md`, `AGENTS.md`, `docs/java.md`: sincronizados con el estado real (incluido
   quitar el TODO explícito que ya tenía `docs/java.md` sobre el `package-info.java` faltante).

No hubo un paso RED clásico: al no existir ninguna violación real hoy (confirmado por grep antes de
tocar código), activar las reglas de verdad no tenía nada que romper — se verificó en verde
directamente, consistente con la decisión tomada con el usuario en Step A ("solo ArchUnit, sin
romper-y-restaurar adicional").

Verificación por paso: `sh ./mvnw test -Dtest=ArquitecturaTest` en verde tras el paso 1 y de nuevo
tras el paso 2 (4/4 tests).

Un formateador real (`format-on-edit`, hook de `instrument-agent-java`, instalado en F2) reformateó
`ArquitecturaTest.java` completo a `google-java-format` apenas se guardó el primer cambio — el diff
final del archivo es más grande de lo que las ediciones puntuales sugieren, por esto.

## Step G — Gates

`make ci` completo desde `base-conocimiento/` (no `make check` — decisión explícita de la revisión
adversarial, ver F3): build + 122 tests (0 failures, 2 skipped de `RrfFusionTest`, no
relacionados) + `gitleaks detect` sin hallazgos nuevos.

**Hallazgo de entorno real durante este paso:** `gitleaks`, instalado hoy vía `winget`, todavía no
estaba en el `PATH` de la sesión de shell — el primer intento de `make ci` falló en el paso
`secrets` con "no se puede encontrar el archivo especificado", no por un secreto real. Se corrió
`gitleaks detect` a mano con la ruta completa; el primer intento manual, ejecutado desde la raíz
del monorepo, siguió reportando los 5 falsos positivos ya conocidos de F2 — porque `gitleaks` busca
`.gitleaksignore` en el directorio desde el que se invoca, y ese archivo vive en
`base-conocimiento/`, no en la raíz. Repetido desde `base-conocimiento/`: `no leaks found`, igual
que corre `make ci` de verdad (su target `secrets` ya tiene el `cwd` correcto).

`/code-review` (nivel medio, solo revisión) sobre el diff: sin hallazgos de corrección ni
seguridad. Corrió sobre el diff completo de la rama contra `main` (338 archivos, arrastra todo
F0-F2) más los cambios de este ticket encima — no aisló los 5 archivos de este issue en su resumen,
pero sí los incluyó en el análisis; se complementó con una revisión manual línea por línea del diff
específico de estos 5 archivos antes de commitear.

## Step H — Commit, push, PR

- Commit real (`eb889c2`) con `Closes #2`, staged explícitamente (nunca `git add -A`). El hook
  `pre-commit` de Lefthook corrió de verdad (`base-conocimiento-format`, 15s).
- `git push -u origin feature/2-archunit-seguridad-allowemptyshould`: el hook `pre-push` de
  Lefthook corre la suite completa antes de dejar salir el push — se pasó del timeout de 2 minutos
  de la primera terminal usada y hubo que reintentarlo en background con más margen. No es un
  fallo, es la suite real (122 tests + compilación) tomando más de 2 minutos.
- PR real: [`#3`](https://github.com/G3A/workshop-desarrollo-sw-guiado-por-ia/pull/3), contra
  `dev` (mismo destino que `#1`), con el resumen de cambios y los comandos de verificación
  realmente corridos.

## Step I — CI

`gh pr checks 3 --watch` real: un job en verde (`pass`, 2m22s), un segundo run marcado `skipping`
(duplicado por el evento de push + el de apertura del PR sobre la misma rama, no un fallo). Sin
comentarios de revisión pendientes (`gh pr view --comments` y la API de comentarios inline, ambos
vacíos). `mergeStateStatus: CLEAN`, `mergeable: MERGEABLE`.

## Step J — Wrap-up

Comentario real posteado en el issue `#2` con el resumen final (link al PR, qué cambió, qué se
verificó). `STATUS→IN-REVIEW` se saltó deliberadamente — ver la resolución de `STATUS` en
`f3-planificar.md`: ni labels ni Projects v2 son un mecanismo confirmable en este repo hoy.

## Resultado final

PR `#3` abierto contra `dev`, CI en verde, sin comentarios pendientes, issue `#2` comentado. El
merge queda **descrito, no ejecutado** — coherente con la regla de esta validación de nunca tocar
ramas más allá de lo que cada etapa pide (F6 es la etapa que decide sobre el merge).
