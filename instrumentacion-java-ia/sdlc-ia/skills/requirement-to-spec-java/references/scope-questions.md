# Agree on scope

> Called from **Phase 3** of `SKILL.md`. Uses `AskUserQuestion` exclusively — max 4 questions per
> call, no jargon a business reader would not already use, and never a question Phase 1 already
> answered.

## Six categories, always active

Every run checks all six — not because every requirement needs a question from each, but because
skipping a category silently is how a real gap goes unnoticed. A category with nothing to ask
about produces no question from it, not an assumption standing in for one.

1. **Public contract impact.** If Phase 1 found a contract surface the requirement touches, ask
   whether it may change incompatibly or must stay compatible — never infer this from the
   requirement's tone. Present the concrete surface found (`ChatController.previsualizar`, not
   "the chat API") so the question is answerable without re-reading the source.
2. **Tabular cross-check**, only when Phase 1 found both a database MCP server and the requirement
   document has tabular data attached (a spreadsheet, a table pasted into a Word doc). Ask whether
   that data should be cross-checked against the real schema before the spec assumes it is
   accurate — do not run the cross-check without asking first, it can be a slow, wide query.
3. **Documentation obsolete.** Present the Phase 1 citation list (file, line, quoted text) and ask
   which the user wants updated as part of this work versus left for later — never decide this
   silently, and never present a document without the citation that put it on the list.
4. **Missing attachments.** Report, don't ask — if Phase 1 could not read an attachment the
   requirement references, that is stated as a Phase 1 finding and repeated in Phase 6, not turned
   into a question. Asking "what does the missing file say?" defeats the point of never inventing
   content — the user attaching the real file is the only real fix.
5. **Ambiguity sweep.** Categories worth a pass, in order: actors (who does this, precisely —
   "a user" is rarely precise enough for an acceptance criterion), triggers (what starts this
   flow), data shape (what does the requirement assume about a field's type or presence that the
   entity does not guarantee), and edge cases the document's happy path skips. Record every answer
   as a **Decision** (the document or the user settled it) or an **Assumption** (nobody settled it,
   you are proceeding on a stated guess) — carried verbatim into the spec, the same distinction
   `github-plan-build`'s Step A uses for exactly the same reason: an Assumption is a checkpoint
   Phase 5 has to be able to catch, a Decision is closed.
6. **Validation criteria.** Two questions, in double form: what proves this requirement is
   satisfied (the acceptance signal), and what would prove it is *not* — a requirement with only a
   positive criterion tends to under-specify its edge cases.

## Destination, always asked

Present every tracker Phase 1 detected plus "Local file" — never auto-select even when only one
tracker exists. This is a separate question from the six above, asked once, not folded into the
ambiguity sweep.

## Deriving the task breakdown

Once scope is settled: functional tasks first, in the order the feature would actually be built
(schema before service before endpoint before UI, when several apply) — documentation tasks
follow the functional work they describe, never precede it. Each task should be small enough that
`github-plan-build`'s own Step C ("small steps, each naming the exact file(s)") could turn it into
one commit without further breakdown — if a task is bigger than that, split it here rather than
leaving the split for later.
