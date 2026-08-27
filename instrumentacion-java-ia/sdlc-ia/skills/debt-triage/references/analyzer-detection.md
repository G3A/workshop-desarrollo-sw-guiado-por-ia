# Analyzer detection and how to pull findings

One row per analyzer. Detect from CI/config first; fall back to running the tool's own report
target if the repo has one but no fresh report on disk.

| Analyzer | Detect | Pull findings |
|---|---|---|
| SonarQube / SonarCloud | `sonar-project.properties`, a `sonar:sonar`/`sonar-scanner` CI step | `curl` the API: `GET /api/issues/search?componentKeys=<key>&resolved=false`, or read the CI step's own report artifact if it uploads one |
| CodeQL | `.github/workflows/*codeql*`, `.github/codeql/*` | `gh api repos/{owner}/{repo}/code-scanning/alerts?state=open` |
| ESLint | `.eslintrc*`, an `eslint` script in `package.json` | Run `eslint . -f json` if no fresh report exists; a stale report is worse than a fresh run |
| Checkstyle | `checkstyle.xml`, a Maven `checkstyle` plugin entry | `target/checkstyle-result.xml` after `mvn checkstyle:check`, or the CI artifact |
| PMD | `pmd-ruleset.xml`, a Maven/Gradle `pmd` plugin entry | `target/pmd.xml` / `build/reports/pmd/*.xml` |
| SpotBugs | `spotbugs-exclude.xml`, a Maven/Gradle `spotbugs` plugin entry | `target/spotbugsXml.xml` / `build/reports/spotbugs/*.xml` |
| golangci-lint | `.golangci.yml` | `golangci-lint run --out-format json` |
| ruff / flake8 (Python) | `ruff.toml`, `pyproject.toml` with a `[tool.ruff]`/`[flake8]` section | `ruff check . --output-format json` / `flake8 --format=json` (needs a plugin) |

When two tools cover the same rule family (e.g. ESLint's `security/` plugin and CodeQL both flag
injection), triage the finding once and note both sources in the report — don't count it twice.

A finding pulled from a **stale** report (older than the current HEAD) must be re-verified against
the current file before triage — the line it points to may already be gone.
