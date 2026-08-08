# Domain glossary

Terms with a precise meaning in this codebase. Use these words in code, commits,
and discussion; sharpen them here when they drift.

## AI editing

- **Edit Run** — a scoped, batched, reviewable AI edit pass over library
  documents. Owned by `AiEditRun` (core.app): holds the run's `processed` docId
  set and the `appliedSoFar` accumulator. One run spans a whole edit session
  (its `processed` set survives cancels and scope changes) and is consumed
  batch by batch via `suggestNextBatch` and `apply`.
- **Scope** — which documents an Edit Run covers: `SELECTION`, `UNTAGGED`,
  `REVIEW_QUEUE`, `MISSING_FIELDS`, `SAVED_VIEW`, `ALL`. Resolution to a
  `LibraryFilter` (including SELECTION's in-memory intersection and
  MISSING_FIELDS' emptiness guard) lives in `AiEditRun.docsFor(scopeKey)` —
  the single definition of what each scope means, shared by the preview count
  and the run itself.
- **Review** — the phase where suggested edits await human acceptance. The
  **pre-select policy** is domain logic in core: additions of existing-vocabulary
  tags are pre-selected; new tags and all removals are pre-deselected; metadata
  fill-ins (empty current value) are pre-selected; overwrites are pre-deselected.
- **Apply** — writing accepted suggestions to the journal. Tag writes are
  grouped per distinct tag and are all-or-nothing per tag. Partial failure
  yields a Retry outcome: a rebuilt Review containing only failed documents,
  with already-succeeded items deselected so retry never re-writes.

The UI's `EditPhase` (Idle/Suggesting/Review/Applying/Done) is presentation
state in `composeApp` and is not part of the domain vocabulary.
