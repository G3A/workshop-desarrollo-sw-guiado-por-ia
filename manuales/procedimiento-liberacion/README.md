# Procedimiento para hacer una liberación

Cómo se publica una versión del plugin `sdlc-ia`: lo que está en `dev` llega a `main` con la
versión subida y el CHANGELOG fechado. Son dos PR seguidas, en la misma sesión. El porqué de este
esquema está en el [ADR 0001](../../docs/adrs/0001-liberar-con-chore-release-a-dev.md), y las
reglas de ramas, en el [`AGENTS.md`](../../AGENTS.md) de la raíz.

Los comandos de `git` y `gh` son iguales en PowerShell y en bash. Donde difieren, van los dos.
En los ejemplos, `<versión>` es la versión nueva (por ejemplo `0.4.2`) y `<N>` es el número de una
PR o un issue.

## 0. Antes de empezar

- **`dev` está en verde** y se decidió publicar. Abrir la PR del paso 1 *es* esa decisión; no se
  abre «por si acaso».
- **Qué entra.** Los commits de `dev` que todavía no están en `main`:

  ```
  git fetch origin
  git log --oneline origin/main..origin/dev --no-merges
  ```

- **Qué número le toca.** Mira la sección `## [unreleased]` de
  `instrumentacion-java-ia/CHANGELOG.md`. Si trae `### Added` (una skill o una capacidad nueva),
  sube el número del medio, como la `0.3.0` → `0.4.0`. Si solo trae `### Changed` o `### Fixed`, es
  un parche, como la `0.4.0` → `0.4.1` y la `0.4.1` → `0.4.2`.
- **Docker Desktop encendido.** El pre-push corre `make check` en `base-conocimiento/`, y sus tests
  de integración levantan PostgreSQL con Testcontainers. Con Docker apagado, el push falla con
  `Can't get Docker image: pgvector/pgvector:pg18-trixie`. El pre-push tarda entre 2 y 3 minutos.

## 1. PR `chore/release-<versión>` hacia `dev`

Lleva solo dos cambios:

- `instrumentacion-java-ia/sdlc-ia/.claude-plugin/plugin.json`: `"version": "<anterior>"` →
  `"version": "<versión>"`.
- `instrumentacion-java-ia/CHANGELOG.md`: `## [unreleased]` → `## [<versión>] — <fecha>`, con la
  fecha en formato `AAAA-MM-DD`. Es la fecha del día en que la liberación llega a `main`.

La rama sale de `origin/dev` recién traído, porque el Ruleset `integration-dev` exige la rama al día:

```
git fetch origin
git checkout -b chore/release-<versión> origin/dev
# edita los dos archivos
git add instrumentacion-java-ia/sdlc-ia/.claude-plugin/plugin.json instrumentacion-java-ia/CHANGELOG.md
git commit -m "chore(release): <versión>"
git push -u origin chore/release-<versión>
gh pr create --base dev --title "chore(release): <versión>" --body "Libera <versión>: sube plugin.json y fecha el CHANGELOG (ADR 0001)."
```

Si el commit lo asistió una IA, el mensaje termina con el trailer `Asistido-por-IA: <modelo>` en su
propio párrafo.

Cuando `CI / check` sale en verde, se mergea con **squash**, como toda PR a `dev`:

```
gh pr merge <N> --squash --admin
```

`--admin` usa el bypass del rol Administrador. Mientras el repo tenga una sola persona hace falta,
porque nadie puede aprobar su propia PR. Si la PR ya se mergeó desde la web, `gh` responde
`was already merged` y no pasa nada.

## 2. PR `dev` → `main`, apenas entra la anterior

Primero, qué issues se cierran. Son los `Closes #N` que traen los commits de `dev`:

```powershell
# PowerShell
git log origin/main..origin/dev --format=%B | Select-String 'Closes #'
```

```bash
# bash
git log origin/main..origin/dev --format=%B | grep 'Closes #'
```

Después se abre la PR. El salto de línea del cuerpo se escribe distinto en cada shell:

```powershell
# PowerShell: el salto de línea es `n
gh pr create --base main --head dev --title "Liberación <versión>" --body "Libera <versión> (ADR 0001).`n`nCloses #<N>"
```

```bash
# bash: el salto de línea va con $'...\n...'
gh pr create --base main --head dev --title "Liberación <versión>" --body $'Libera <versión> (ADR 0001).\n\nCloses #<N>'
```

Los checks que tiene que mostrar la PR, todos en verde:

| Workflow / job | Corridas | Por qué |
|---|---|---|
| `CI / check` | 2 | una por el `push` a `dev` y otra por la `pull_request`; las dos corren enteras |
| `Liberacion / changelog-liberado` | 1 | solo existe en PRs hacia `main` |

**Ningún check requerido puede salir saltado** (`skipped`). GitHub cuenta un job saltado por un `if`
como exitoso para un check requerido, así que una copia saltada podría tapar un rojo (#132). Si
aparece una, algo en `.github/workflows/` volvió a la forma vieja y hay que corregirlo antes de
liberar.

Para ver los check runs del commit con su conclusión:

```
gh pr checks <N>
```

Se mergea con **merge commit**, no con squash (Ruleset `release-main`), para conservar los commits
de `dev` tal cual, con sus `Closes` y sus trailers:

```
gh pr merge <N> --merge --admin
```

## 3. Después

- **Verifica que quedó publicada:**

  ```
  git fetch origin
  git show origin/main:instrumentacion-java-ia/sdlc-ia/.claude-plugin/plugin.json
  gh issue view <N> --json state
  ```

  `plugin.json` en `main` dice la versión nueva, y los issues del `Closes` quedan `CLOSED`.
- **Borra la rama de la liberación:** `git push origin --delete chore/release-<versión>` y
  `git branch -D chore/release-<versión>`.
- **En cada equipo:** el plugin carga en su lugar desde el clon, así que lo que corre es lo que
  ese clon tiene en disco. Para tomar la versión nueva, corre el script de actualización desde
  `instrumentacion-java-ia/`, en un clon que esté en `main`: trae la versión, la re-registra para
  que `claude plugin list` la muestre y verifica que la CLI cargue desde esa carpeta. Aplica en la
  próxima sesión o con `/reload-plugins`.

  ```powershell
  .\update.ps1        # Windows PowerShell 5.1 o PowerShell 7
  ```

  ```bash
  ./update.sh         # macOS, Linux, Git Bash
  ```

## Si algo falla

| Síntoma | Causa | Qué hacer |
|---|---|---|
| El pre-push falla con `Can't get Docker image` | Docker Desktop apagado | Enciéndelo, espera a que `docker info` responda y vuelve a hacer el push |
| `changelog-liberado` falla con «todavía empieza con [unreleased]» | Te saltaste el paso 1 | Haz la PR `chore/release-<versión>` a `dev`; la PR hacia `main` vuelve a correr sola |
| La PR sale `BLOCKED` con todos los checks en verde | Falta la aprobación que piden los Rulesets | Mergea con `--admin` |
| Entró una feature a `dev` entre el paso 1 y el 2 | La ventana que describe el ADR 0001 | Libérala igual con esa versión, o abre otra `chore/release` con la versión siguiente. Nunca le quites la fecha a una versión |
