# Domain glossary

Terms with a precise meaning in this codebase. Use these words in code, commits,
and discussion; sharpen them here when they drift.

## Fields

- **Field** — one named value on a document, identified by a stable **key**
  (`FieldKeys.normalize`d, case-insensitively unique) and written as a `field-set`
  journal op with HLC last-writer-wins. Title, correspondent, document type,
  document date and notes are fields like any other; nothing in the code path is
  specific to them beyond the XMP interop properties and the title's file-name
  fallback. What a model is told about them is their seeded definition: type,
  flags and hint.
- **Field definition** (`FieldDef`) — the registry entry for a key: label, `FieldType`
  (TEXT, LONG_TEXT, NUMBER, DECIMAL, DATE), optional hint, and three flags:
  **showByDefault** (render on every document and in the library filter, empty or
  not), **aiEnabled** (asked for in AI edit runs and filled by automatic
  enrichment) and **perDocument** (the value is the document's own, like its
  title: no vocabulary is shared, and a model writes it in the app's language).
  Synced through `field-def` ops; edited in Settings → Documents.
- **Well-known field** — one of the five keys a fresh library is seeded with
  (`FieldKeys.WELL_KNOWN`). Their type is locked because other subsystems read
  them as that type; everything else about them is editable. A change to the
  seeds raises `FieldKeys.SEED_VERSION`, and older libraries are upgraded once
  without overriding what their users changed.
- **Canonical form** — the one stored representation of a value for its type
  (`FieldValues`): trimmed text, `yyyy-MM-dd`, `-?\d+`, `-?\d+(\.\d+)?`. Locale
  formatting happens only at the UI boundary. Blank canonicalizes to "no value":
  a cleared field has no row, so "missing" is exactly "no row". Each number has
  one canonical form (`7`, `1.5`); values older builds stored as `007` or `1.50`
  are read through `FieldValues.normalized`.
- **Orphan value** — a value whose key has no definition (the definition was
  removed without clearing values, or a sync brought a key this device has not
  defined). Still stored, searchable and shown in a muted group; "Add to fields"
  promotes the key into the registry.
- **System property** (`SystemProperty`) — per-document state the app owns rather
  than the user: `reviewed`, `ocrEngine` and `storagePath`. Written as `prop-set`; never a field.

## Library

- **Review queue** — the documents still waiting for the user, by the library's
  `ReviewMode`: the untagged ones (`UNTAGGED_ONLY`), or with the inbox workflow
  (`INBOX_WORKFLOW`) the ones not marked reviewed or untagged
  (`reviewQueueFilter`, `observeReviewQueueCount`). With the inbox workflow the
  user-facing name is **Inbox** ("Needs review · N" on the library's chip), and
  without it "Untagged documents"; never "review queue", which only code and the
  AI edit scope (`REVIEW_QUEUE`) keep. Not the AI edit **Review** below.

## AI editing

- **Edit Run** — a scoped, batched, reviewable AI edit pass over library
  documents; the UI calls it a **Bulk edit**, the assistant's second mode beside its chat. Owned by `AiEditRun` (core.app.aiedit): holds the run's `processed` docId
  set. One run spans a whole edit session
  (its `processed` set survives cancels and scope changes) and is consumed
  batch by batch via `suggestNextBatch` and `apply`.
- **Queued Edit Run** — an Edit Run whose *suggestion* half was handed to the
  background: `AiEditQueue` (core.app.aiedit) persists the run's request and its
  documents, buys one model call per document on the `QueueDrainer`, and stores
  each answer verbatim. Nothing is written unattended — the run turns into an
  `EditRunReview` on the processing queue (**Activity** in the UI) and goes through the same human
  Review and Apply as an interactive batch. That is the whole difference from
  automatic enrichment (**Auto-organize** in the UI), which may write because its policy is fixed.
  A queued run covers the WHOLE scope, not one batch: the batch cap exists to
  bound what a human reviews in one sitting, and nobody is sitting. It is the
  default above `AiEditLimits.BACKGROUND_THRESHOLD_DOCS` (composeApp) documents, and the user
  can flip it either way per run.
  Failure is read two ways: a partly failed chunk is the *documents'* fault and
  charges them attempts (given up on at `MAX_DOC_ATTEMPTS`); a wholly failed one
  is read as an endpoint outage and charges only the run's backoff — but only
  for `MAX_JOB_ATTEMPTS` retries, after which it too is charged, because "the
  whole chunk failed" says nothing once the chunk is one document.
- **Scope** — which documents an Edit Run covers: `SELECTION`, `UNTAGGED`,
  `REVIEW_QUEUE`, `MISSING_FIELDS`, `SAVED_VIEW`, `ALL`. Resolution to a
  `LibraryFilter` (including SELECTION's document ids, an empty selection
  selecting nothing, and MISSING_FIELDS' emptiness guard) lives in
  `AiEditRun.docsFor(scopeKey)` —
  the single definition of what each scope means, shared by the preview count
  and the run itself.
- **Review** — the phase where suggested edits await human acceptance (not the
  library's review queue, which users know as the Inbox). The
  **pre-select policy** is domain logic in core, owned by `ReviewProjector`:
  additions of existing-vocabulary tags are pre-selected; new tags and all
  removals are pre-deselected; metadata fill-ins (empty current value) are
  pre-selected; overwrites are pre-deselected. A stored suggestion is projected
  *late* — when the user opens it — so "would this overwrite something" is
  decided against the document as it is now, not as it was when the model ran.
  `ReviewApply` owns the step from a reviewed batch to the journal: the
  selection projection plus the Retry rebuild, shared by both flows so an
  interactive and a queued run cannot drift apart.
- **Apply** — writing accepted suggestions to the journal. Tag writes are
  grouped per distinct tag and are all-or-nothing per tag; field writes are one
  `applyEdits` per document, because a setter per field re-runs the storage path
  rules and renames the file once per field. Partial failure yields a Retry
  outcome: a rebuilt Review containing only failed documents, without the
  already-succeeded items; whatever a retry selects, the batch never writes a
  suggestion that landed again.
  Apply is owned by `ReviewApply` (core.app.aiedit), one per reviewed batch, shared by
  the interactive and the queued Edit Run. It also carries what earlier attempts
  of the batch wrote, so the Done that ends a run of retries counts all of it,
  as does the Done of skipping or discarding the rest after a partial Apply.
  Automatic enrichment writes at a different grain and does not go through it:
  it writes ONE unattended document and, on failure, re-asks the model, so it
  commits the whole edit — tags and fields — in a single `applyEdits`, so a
  failed pass writes nothing and its retry starts from the state the model was
  originally shown. What it may write is binary: new tags and removals are
  dropped outright. Review pre-selection is tri-state instead (a new tag stays
  visible as a deselected chip the user can opt into); the two must not be
  collapsed into one rule.

The UI's `EditPhase` (Idle/Suggesting/Review/Applying/Queued/Done) and
`QueuedReviewPhase` are presentation state in `composeApp` and are not part of
the domain vocabulary.

## Transcript

- **Transcript** — the running chat conversation as an ordered list of rendered
  items (user turn, tool use, assistant turn). `ChatTranscript` (composeApp,
  `ui.ai`) is an immutable value type and the single definition of the fold
  rules that turn a stream of `AiChatEvent`s into items; `AiChatViewModel` only
  drives it and owns the coroutine/effect plumbing.
- Load-bearing fold rules: a tool call ends the current narration turn (pre-tool
  text is finalised into its own bubble, never merged with post-tool text or the
  run's full answer); each finalised assistant bubble carries only its own
  docRefs; finalising keeps whatever partial text streamed.
- The transcript is display state only. The next ask sees the conversation the
  session persisted (`RoomChatHistoryProvider`), not this fold — the caller
  passes a session id and a question, never a history.
- `EditPhase`-style presentation state stays out of `ChatTranscript` — the
  reducer is pure, with no snackbar/message/effect logic.

## Library integrity

- **Verify library** — the read-only check behind Library settings' action of that name:
  `MaintenanceFacade.verifyLibrary` walks every document the journal shows (file present, readable,
  stored size, SHA-256 equal to its id) and every journal object (bytes against the hash in its
  name). It reports problems, a document the library already lists as missing among them, and
  changes nothing. It runs as the session's work, so it outlives the settings screen.
- **Sync health** — how cloud sync is doing without a live remote reading: the last success and its
  date, the consecutive-failure streak, the next scheduled pass, the typed last failure, and the
  pending uploads/downloads the last plan found and did not finish ("as of the last check").

## Library session facets

`LibrarySession` (core.app) is a pure aggregate of narrow facets plus lifecycle
(`start`/`close`); `LibrarySession.kt` lists them, and each facet's contract file
says what it owns. Viewmodels take the session and read the facets they use from
it; code in `core` depends on the facets alone.

No endpoint configuration crosses the AI facets: `AiConfig` belongs to settings,
and whether the endpoint is configured, which hides or disables AI features, is
read from the settings too (`AppViewModel.aiConfigured`, handed to the screens).
