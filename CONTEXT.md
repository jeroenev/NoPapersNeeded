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
  than the user: `reviewed` and `ocrEngine`. Written as `prop-set`; never a field.

## AI editing

- **Edit Run** — a scoped, batched, reviewable AI edit pass over library
  documents. Owned by `AiEditRun` (core.app): holds the run's `processed` docId
  set. One run spans a whole edit session
  (its `processed` set survives cancels and scope changes) and is consumed
  batch by batch via `suggestNextBatch` and `apply`.
- **Queued Edit Run** — an Edit Run whose *suggestion* half was handed to the
  background: `AiEditQueue` (core.app) persists the run's request and its
  documents, buys one model call per document on the `QueueDrainer`, and stores
  each answer verbatim. Nothing is written unattended — the run turns into an
  `EditRunReview` on the processing queue and goes through the same human
  Review and Apply as an interactive batch. That is the whole difference from
  automatic enrichment, which may write because its policy is fixed.
  A queued run covers the WHOLE scope, not one batch: the batch cap exists to
  bound what a human reviews in one sitting, and nobody is sitting. It is the
  default above `AiTagging.BACKGROUND_THRESHOLD_DOCS` documents, and the user
  can flip it either way per run.
  Failure is read two ways: a partly failed chunk is the *documents'* fault and
  charges them attempts (given up on at `MAX_DOC_ATTEMPTS`); a wholly failed one
  is read as an endpoint outage and charges only the run's backoff — but only
  for `MAX_JOB_ATTEMPTS` retries, after which it too is charged, because "the
  whole chunk failed" says nothing once the chunk is one document.
- **Scope** — which documents an Edit Run covers: `SELECTION`, `UNTAGGED`,
  `REVIEW_QUEUE`, `MISSING_FIELDS`, `SAVED_VIEW`, `ALL`. Resolution to a
  `LibraryFilter` (including SELECTION's in-memory intersection and
  MISSING_FIELDS' emptiness guard) lives in `AiEditRun.docsFor(scopeKey)` —
  the single definition of what each scope means, shared by the preview count
  and the run itself.
- **Review** — the phase where suggested edits await human acceptance. The
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
  Apply is owned by `ReviewApply` (core.app), one per reviewed batch, shared by
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

## Library session facets

`LibrarySession` (core.app) is a pure aggregate of narrow facades plus
lifecycle (`start`/`close`). Viewmodels take the session and read the facets
they use from it; code in `core` depends on the facets alone. Each operation has
exactly one public home:

- **Reads** — `documents` (`DocumentRepository`: lists, filters, counts and a
  document's history), `tags` (`TagRepository`) and `content` (`DocContentFacade`:
  thumbnails, page renders, extracted text, baked copies and bake-in-place).
- **Library settings** — library data kept in the journal, so every device
  shares it: the storage-path rule (`StoragePathFacade`, session property
  `storagePath`), the auto-match rules (`AutoMatchRuleRepository`, `rules`) and
  saved views (`SavedViewRepository`, `savedViews`).
- **Ingest** (`IngestFacade`) — getting documents into the library: `importPdf`,
  `importPdfBytes`, `importImagesAsPdf`, `rescan`, plus the `scanProgress` and
  `lastScanReport` flows.
- **OCR ops** (`OcrFacade`) — text-recognition management per document: `reOcr`,
  `ocrVersions`, `setOcrEngine` (the single public home for the engine pin),
  `aiOcrOffer` and `ocrWithAi` (a transcription by the vision model, asked for
  on this device), plus the `ocrStatus` flow.
- **Review Config** (`ReviewConfigFacade`) — library-wide review settings:
  `reviewMode`/`setReviewMode`, `markReviewedForMigration`.
- **Field Registry** (`FieldRegistry`, session property `fieldRegistry`) — the
  library's field definitions: the `fields` flow and `current()`, `upsert`,
  `remove(key, clearValues)`, `reorder`, plus the values vocabulary
  (`distinctValues`), `orphanKeys` and `usageCounts`.
- **Maintenance** (`MaintenanceFacade`) — repair and surgery on existing
  documents: `resolveDuplicate`, `applyRelink`, `splitPdf`.
- **Metadata writing** (`MetadataWriter`, session property `meta`) — per-doc and
  bulk edits only: tags on docs, the reviewed flag, and field values by key
  (`setField`, `applyEdits`), canonicalized against the registry type.
- **Tag Admin** (`TagAdmin`, session property `tagAdmin`) — vocabulary-level tag
  operations: `renameTag`, `mergeTag`, `deleteTag`, `setTagStyle`.
- **Doc Lifecycle** (`DocLifecycle`, session property `docLifecycle`) — a
  document's existence and location: `trashDoc`, `restoreDoc`,
  `deleteDocPermanently`, `renameFile`, `moveFile`.
- **AI chat** (`AiChat`, session property `aiChat`) — the assistant
  conversation: `ask` plus session management (`chatSessions`,
  `createChatSession`, `deleteChatSession`, `clearChatSession`,
  `chatTranscriptFor`).
- **AI suggestions** (`AiSuggest`, session property `aiSuggest`) — the single
  suggestion primitive `suggestEdits`. No consumer needs both AI facets.
- **Work** (`WorkFacade`, session property `work`) — the persisted background
  queues. Three drains, `drainOcr`, `drainEnrichment` and `drainEditRuns`: each
  drains until no further progress is possible right now (queue empty, feature
  off, endpoint unconfigured, or a backoff pending) and returns; it never waits
  out a backoff. For callers that must hold a process open while background work
  runs: Android's WorkManager workers. Plus two reads: `queue`, which
  flattens the per-document tables into `QueuedWork` entries (outstanding work
  only), and `editRuns`, the queued AI edits at batch grain with finished ones
  first. The two stay separate because they differ in grain, in lifetime (the
  first clears itself, the second waits for a human) and in what the user can do
  with them.
- **AI edit queue** (`AiEditQueueFacade`, session property `aiEditQueue`) —
  handing a bulk AI edit to the background and picking it back up:
  `enqueueEditRun`, `openEditRun`, `discardEditRun`.

Both AI facets extend **`AiAvailability`** (`configured()`): the only piece of
the endpoint configuration that crosses the seam is that boolean — `AiConfig`
itself belongs to settings.
