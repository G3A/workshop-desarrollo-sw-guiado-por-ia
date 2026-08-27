# Architecture Decision Records (ADRs)

This directory contains **ADRs** — short, dated records of architecturally significant decisions. They explain *why* the code looks the way it does, which is harder to infer from reading the source than *what* it does.

## When to write one

Write an ADR when you:
- Choose between two or more viable options (build tool, persistence framework, pattern, tool).
- Accept a trade-off future contributors will want to revisit.
- Adopt a convention that isn't enforced by tooling (or that is, like a boundary between Spring Modulith modules — worth writing down why it exists).

Don't write one for reversible implementation details.

## Format

Use `adr-template.md` as a starting point. Each ADR has four sections:

1. **Status** — Proposed / Accepted / Superseded.
2. **Context** — the problem, the options considered, and the constraints.
3. **Decision** — what we chose.
4. **Consequences** — what gets easier (**Pros**), what gets harder (**Cons**).

Keep ADRs short (1 page). Name them `NNNN-short-slug.md` with a zero-padded counter.

## Index

<!-- TODO: list ADRs as they're created. -->

- `0001-...` — <!-- title -->
