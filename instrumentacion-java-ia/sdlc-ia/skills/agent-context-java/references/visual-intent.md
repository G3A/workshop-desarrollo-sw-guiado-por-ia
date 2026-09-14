# Visual intent — discovery for `design.md`, `docs/design-tokens.md` and `COMPONENTS.md`

> Called from **Phase 1g**, **Phase 2** (optional docs) and **Phase 3** of `SKILL.md`.

**The hard rule: read, never propose.** These three files record the tokens and components the
repository **already has**. Where the repository says nothing, the file says so and leaves a TODO.
Never write a spacing scale, a type ramp, a colour palette, colour roles or "design best
practices". Inventing a team's design decisions is the same hallucination as inventing its risk
posture (`EXPERIMENTS.md`).

---

## 1. Is there a user interface?

Decides whether the visual-intent option is offered at all. Look for, and report which matched:

| Signal | Where |
|---|---|
| Server-rendered templates | `src/main/resources/templates/**` — Thymeleaf, JTE, Freemarker, Mustache |
| Static assets served by the app | `src/main/resources/static/**`, `src/main/resources/public/**` |
| A front-end subproject | `package.json` with a UI framework dependency, `angular.json`, `vite.config.*`, `next.config.*`, `svelte.config.*` |
| View-returning controllers | `@Controller` **without** `@ResponseBody` (as opposed to `@RestController`), or a `ModelAndView` return type |
| A browser test suite already there | Playwright, Cypress, Selenium or `@SpringBootTest` with a `WebDriver` |

This table is copied from `instrument-agent-java`'s discovery checklist, item 8b, so both skills
answer "is there a UI?" the same way — keep them identical.

- **No signal → no UI.** Do not offer the option and do not write any of the three files. Report
  "no user interface found" with the signals you checked. The negative result is a real answer,
  not a failed check.
- **Only a browser test suite pointed at another service** → there is nothing of this repository's
  own to read. Report that case by name and do not offer the option.
- **A UI signal matched** → the matched directories are the **UI roots** for sections 2 and 4 —
  except a browser test suite's own directory: it proves there is something to look at, but it
  holds tests and their reports, not the UI.

## 2. Where tokens live

Search **only tracked files** (`git ls-files <ui-root>` — the same in PowerShell and bash) and
**only inside the UI roots** from section 1. That keeps out build output, `node_modules/`,
`vendor/`, `*.min.*`, and tracked files that merely contain HTML, such as generated reports.

| Signal | Where |
|---|---|
| CSS custom properties (`--name: value`) | `*.css`, **and inside `<style>` blocks in `*.html` and templates** |
| Preprocessor variables | `$var` and `$map: (…)` in SCSS, `@var` in LESS |
| Tailwind **v3** | `tailwind.config.*` → `theme` / `theme.extend` |
| Tailwind **v4** | **`@theme` blocks in the CSS entry point** (and in files it `@import`s; `@theme inline` too). v4 does not auto-detect `tailwind.config.*`; a JS config is used only when a CSS file names it with `@config` |
| Standard-format tokens | `*.tokens` / `*.tokens.json` ([DTCG 2025.10](https://www.designtokens.org/tr/2025.10/format/), first stable version 2025-10-28): `$value`, `$type` (on the token or its group), aliases written `{group.token}` |

Verified on **2026-09-14** against the DTCG 2025.10 format specification and Tailwind CSS's own
docs (`/docs/theme`, `/docs/upgrade-guide`). Re-verify before changing this table.

A `*.tokens.json` is **read if present, never written**: generating one would choose a format for
the team.

### The three mistakes discovery must not make

1. **Searching only `*.css`.** Tokens often live inside `<style>` in an HTML page. An extension
   search returns zero and the doc says "this repository defines no tokens" — which is false.
2. **Searching only `tailwind.config.js`.** A Tailwind v4 project has none by default and is
   missed entirely.
3. **Keeping one theme.** A light `:root` plus a dark one means **every token has two values**;
   the file records both.

## 3. How to record tokens

One row per **name × source file**. Columns: token · one value column per theme found · source
(`path:line`, path **relative to the project root**, so the file reads the same wherever the
project is checked out).

**What counts as a theme** — one column each:

- the base definition (`:root`, `html`);
- `@media (prefers-color-scheme: dark)` / `(… light)`;
- `[data-theme="…"]`, or a `.dark` / `.light` class on the root element;
- `light-dark(a, b)` in a single definition supplies **two** values — split it into the light and
  dark columns.

**Not themes** — list them under "Contextual overrides", never as a column: `@media` by width,
`print` or `@supports`; custom properties scoped to a component selector (`.sidebar { --w: … }`).

**Record values exactly as written.** `var(--other)`, `var(--x, fallback)` and a DTCG alias
`{group.token}` stay as written, not resolved. A name missing from a theme is **"not defined"** —
never a copy of another theme's value. A name repeated inside the same block: the last one wins
(that is what the browser does); record it and note the repetition.

### More than one source → a finding, not a choice

When the same tokens are defined in more than one file, **never pick the "real" copy and never
unify values.** Write every source and, under "Findings", list:

- the names that exist in only one source, per source;
- the names whose written value differs between sources, with each value and its source.

Choosing silently turns a real inconsistency in the repository into a document that hides it —
and it is exactly what an agent needs to know before writing UI.

## 4. Where components live

| Signal | What to record |
|---|---|
| Thymeleaf fragments — `th:fragment="name"` | fragment name · template path |
| JTE templates — `*.jte` under the template root | template name · path |
| Angular — `*.component.ts` (`selector:`) | selector · path |
| React / Vue — exported components in `*.tsx`/`*.jsx`, `*.vue` single-file components | name · path |
| Storybook — `*.stories.*` | story title · path |

Record name, path and — only if the code states it — the props/parameters. Do not describe what a
component "should" look like.

**Plain HTML and JavaScript with none of these signals has no component structure to discover.**
`COMPONENTS.md` then ships with a TODO and the list of what was searched. That TODO is the correct
output, not a shortfall.

## 5. What gets written

- `docs/design-tokens.md` — sources, the token table, contextual overrides, findings, and "what was
  searched". UI found but no tokens → the file still ships, with a TODO and the search list.
- `COMPONENTS.md` (project root, next to `AGENTS.md` and `REVIEW.md`) — the component table, or the
  TODO and the search list.
- `docs/design.md` — keeps **only "UX principles"** (the one part discovery cannot read) and links
  to the other two. It does not repeat their sections: two copies drift apart.

## 6. Augment mode

- **Existing `docs/design.md`:**
  - a section that is only a TODO is replaced by the link to the new file. If that TODO **states
    something discovery contradicts** (e.g. "the repository has no design tokens" while section 2
    found some), say so in the report;
  - a section with the team's own content is **kept**: add the link at its top and report the
    duplication, so the team decides whether to move it. Never move or delete it.
- **Existing `AGENTS.md`:** append the two links to its list of docs.
- **Existing `docs/design-tokens.md` or `COMPONENTS.md`:** not rewritten without "Overwrite matching
  docs". Compare with what discovery found and report the drift — tokens added, removed or changed
  since the file was written.
