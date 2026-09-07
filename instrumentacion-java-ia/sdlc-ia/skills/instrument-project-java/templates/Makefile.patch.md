# Makefile patch

> Applied by **Phase 3, control 4** of `SKILL.md`.

`base-conocimiento` already has a `Makefile` with `build`/`test`/`verify`/`clean` targets wrapping
`./mvnw`, plus a large infrastructure section (`up`/`down`/`pull-models`/…) unrelated to this
skill. **This is a patch, not a replacement.** Read the real file before touching it — the diff
below is illustrative, not literal; line numbers shift as the infrastructure section grows.

**Language friction, stated rather than hidden:** every existing comment in this Makefile is in
Spanish (`## Muestra esta ayuda`, `## Levanta los 4 servicios`, …), matching the rest of the repo's
prose. The targets this skill adds follow the plugin-wide rule instead — **English**, like every
other config file and Make target this skill writes — so the merged file ends up genuinely
bilingual: Spanish comments above the line this patch does not touch, English comments on the
lines it adds. Do not translate the existing comments as a side effect of this patch, and do not
translate the new targets to match them — that is a decision for a separate, deliberate pass, not
an accident of installing quality gates.

---

## 1. Extend `.PHONY`

The existing line 2 lists every infra/model target. Append the six new ones at the end — do not
reorder what is already there:

```diff
 .PHONY: help up gpu-up up-bonsai down-bonsai up-ministral down-ministral up-qwen35 down-qwen35 \
   up-nemotron down-nemotron up-granite41 down-granite41 up-phi4mini down-phi4mini up-qwen25 \
   down-qwen25 down restart logs ps build test verify pull-models pull-reranker pull-bonsai-gguf \
   pull-ministral pull-qwen35 pull-nemotron pull-granite41 pull-phi4mini pull-qwen25 \
-  pin-embeddings-cpu seed ingest ingest-repos ingest-teams ingest-azdo psql health clean
+  pin-embeddings-cpu seed ingest ingest-repos ingest-teams ingest-azdo psql health clean \
+  format lint secrets check ci hooks
```

## 2. Append a new section after `## ---------------------------------------------------------------- desarrollo`

The existing `build`/`test`/`verify`/`clean`/`psql` targets under that header stay exactly as they
are — do not touch them. Add the six new targets after `clean`, with a header comment consistent
in *shape* with the existing section dividers (`## ---- <name>`) but the description text in
English, since it belongs to the block this skill owns:

```makefile
## ---------------------------------------------------------------- quality gates

format:  ## Apply code formatting (Spotless)
	./mvnw -q spotless:apply

lint:  ## Verify code style and static rules (gate: Spotless + Checkstyle)
	./mvnw -q spotless:check checkstyle:check

secrets:  ## Scan the working tree for committed secrets
	gitleaks detect --no-banner --redact

check: lint build test  ## Single local confidence signal
	@echo "OK -- the repo is green"

ci: lint build test secrets  ## What the CI pipeline runs
	@echo "OK -- CI gates passed"

hooks:  ## Install git hooks (Lefthook)
	lefthook install
```

Notes on this block:

- `format`/`lint` call `./mvnw`, matching the existing `build`/`test`/`verify` targets in this same
  file — do not introduce a bare `mvn` call next to wrapper-based ones.
- `check` and `ci` both depend on `build` (which is `test`, since `test` already runs
  `./mvnw -B test` including the architecture gate) rather than restating `./mvnw` invocations —
  **one definition of "the code is fine"**, the same rule `verify` already follows by wrapping
  `./mvnw -B clean verify`.
- `secrets` is **not** in `check` — mirrors the .NET sibling skill's reasoning: local `check` stays
  fast for the inner loop; `ci` is the one that must catch everything, and `lefthook.yml`'s
  pre-commit already runs `gitleaks protect --staged` on every commit regardless.
- `checkstyle:check` needs a `<configLocation>` pointing at `templates/checkstyle.xml` once it is
  copied to the repo root — wire the plugin in `pom.xml` before this target can run. See the style
  control in `SKILL.md` Phase 3.
- If the repo declined secret scanning in Phase 2, drop the `secrets` target and drop it from `ci`
  in the same edit — a target invoking a tool nobody installed fails the first time someone actually
  runs `make ci`.

## 3. Nothing else changes

`help` is already autodocumented via the `## comment` convention
(`grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST)`) — the new targets show up in `make help` for
free, no edit to the `help` target itself needed.
