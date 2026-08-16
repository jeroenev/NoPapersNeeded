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
lifecycle (`start`/`close`). Consumers depend on the facets they use, never on
the aggregate; only the composable tree and platform entry points hold the
whole session. Each operation has exactly one public home:

- **Ingest** (`IngestFacade`) — getting documents into the library: `importPdf`,
  `importPdfBytes`, `importImagesAsPdf`, `rescan`, plus the `scanProgress` and
  `lastScanReport` flows.
- **OCR ops** (`OcrFacade`) — text-recognition management per document: `reOcr`,
  `ocrVersions`, `setOcrEngine` (the single public home for the engine pin),
  plus the `ocrStatus` flow.
- **Review Config** (`ReviewConfigFacade`) — library-wide review settings:
  `reviewMode`/`setReviewMode`, `standardFields`/`setStandardFields`,
  `markReviewedForMigration`.
- **Maintenance** (`MaintenanceFacade`) — repair and surgery on existing
  documents: `resolveDuplicate`, `applyRelink`, `splitPdf`.
- **Metadata writing** (`MetadataWriter`, session property `meta`) — per-doc and
  bulk edits only: tags on docs, reviewed flag, correspondent, doc type, doc
  date, title, notes, custom fields.
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
  queues: `drainOcr`, `drainEnrichment`. Each drains until no further progress
  is possible right now (queue empty, feature off, endpoint unconfigured, or a
  backoff pending) and returns; it never waits out a backoff. For callers that
  must hold a process open while background work runs — today only Android's
  maintenance job.

Both AI facets extend **`AiAvailability`** (`configured()`): the only piece of
the endpoint configuration that crosses the seam is that boolean — `AiConfig`
itself belongs to settings.
