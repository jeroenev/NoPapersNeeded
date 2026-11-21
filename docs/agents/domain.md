# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the
codebase. Layout: single-context.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root — the domain glossary (Field, Edit Run, Review, ...).
- **`ARCHITECTURE.md`** — module and package map, data flow, the facet contract.
- **`docs/adr/`** — ADRs that touch the area you're about to work in. The folder does not exist
  yet; the `/domain-modeling` skill creates it with the first recorded decision
  (`docs/adr/0001-<slug>.md`). Until then, proceed silently.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a
test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary
explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing
language the project doesn't use (reconsider) or there's a real gap (note it for
`/domain-modeling`).

## Flag ADR and invariant conflicts

If your output contradicts an existing ADR or an architecture invariant in `AGENTS.md`, surface
it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (…) — but worth reopening because…_
