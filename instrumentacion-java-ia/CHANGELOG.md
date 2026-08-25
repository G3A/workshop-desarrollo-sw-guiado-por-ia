# Changelog

All notable changes to the `sdlc-ia` plugin. Versions follow the `version` field in
`sdlc-ia/.claude-plugin/plugin.json`. Dates are the date the work landed on `main`.

## [0.1.0] — unreleased

### Added

- **`agent-context-java`** — generates a documentation pack for a Java/Spring repository
  (`AGENTS.md`, `CLAUDE.md`, `docs/business.md`, `docs/architecture.md`, `docs/data-model.md`,
  `docs/infrastructure.md`, `docs/adrs/`, plus a `docs/java.md` deep-dive) so an AI coding agent
  can reason about it without guessing. Follows a Claimify-inspired claim-validation procedure —
  every load-bearing factual claim is atomized, tagged with its source and confidence, and
  verified with the user before being persisted to `docs/claims-ledger.md`.

- **`instrument-project-java`** — installs eight deterministic controls in a Java/Maven
  repository: reproducible inputs (Maven wrapper pin, BOM-managed dependency versions), a strict
  build (`-Xlint:all -Werror`), verifiable style (Spotless + Checkstyle), a single `make check`
  entry point, Lefthook pre-commit/pre-push gates, gitleaks secret scanning, ArchUnit
  architecture fitness functions, and a CI pipeline (GitHub Actions or Azure DevOps). Every gate
  is proven to fail before the run reports success. When the target repository already has some
  of these controls wired up — `base-conocimiento`, the worked example behind this skill, already
  has ArchUnit + Spring Modulith verification running in `mvn test` — the skill verifies and
  reports findings instead of reinstalling from scratch.

- **`instrument-agent-java`** — registers the team's MCP servers in `.mcp.json` and installs a
  catalogue of seven Claude Code hooks in `.claude/settings.json`, backed by portable, dependency-
  free bash scripts (bash 3.2, no `jq`, `set -u` without `pipefail`): a secret read-guard, scoped
  Spotless auto-formatting, a dangerous-command blocker, a session-start dependency sweep, an
  audit log, and guards for centrally-managed dependency versions and Flyway/Liquibase migration
  files. Two hooks are on by default; the rest are offered, and hidden when the repository doesn't
  have the artifact they protect. Ships with its own regression suite (`tests/`, 146 cases).

- **`github-plan-build`** — the ticket → plan → build → verified-PR loop, targeting GitHub
  Issues. Shares its Steps A–J build-loop procedure with the tracker-agnostic pattern already
  established upstream (`linear-plan-build`/`ado-plan-build`); resolves GitHub's own auto-close
  syntax (`Closes #<n>`) as the issue-linking token, and detects whether the repository tracks
  in-progress/in-review status via labels or GitHub Projects v2 before choosing which one to
  write to.
