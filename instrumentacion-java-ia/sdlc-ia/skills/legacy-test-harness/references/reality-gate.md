# Reality gate — proving a layer is real, not scaffolding

Run this before reporting any layer as generated in Phase 6.

1. **Import check.** Every new test file imports at least one production symbol from the stack's
   real source root (`src/main/java/...`, `src/app/...`, the package under `src/`, ...) — not only
   test-fixture or scaffold code. A test with zero production imports is scaffolding by definition.

2. **Kill check.** For at least one assertion per new test file, temporarily revert or comment out
   the production behavior it targets, run the test, and confirm it **fails**. Restore the
   production code immediately after — this check must never be the reason a production file stays
   modified.

3. **Green check.** With production code restored, run the full new suite and confirm it passes
   clean, with no skipped/pending tests silently counted as generated.

4. **No false safety net.** A test that passes both before and after step 2's revert is not testing
   the behavior it claims to — treat it as scaffolding, not as a generated layer, and rewrite it or
   drop it from the count.

Report the outcome of steps 1–3 per layer in Phase 6, alongside the file list. A layer that fails
any of the three does not get reported as done — say so plainly and either fix it or move it back to
Phase 4.
