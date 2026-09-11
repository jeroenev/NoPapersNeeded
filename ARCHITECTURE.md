# Architecture

How the codebase is organized and how data flows through it. Read this first,
then AGENTS.md for build/test/commit conventions and the invariants you must not
break.

## Big picture

Local-first paperless document manager. A *library* is a plain folder of PDFs
plus a `.npn/` metadata directory; everything else is derived state that can be
rebuilt from the folder. The app runs on Android and Linux desktop from one code
base:

```
androidApp ─┐
            ├─ composeApp (shared UI + viewmodels, KMP commonMain)
desktopApp ─┘
                │
                ▼
             core (platform-agnostic domain + persistence, KMP commonMain)
                │
                └─ platform source sets (androidMain / desktopMain) implement
                   fs, pdf, ocr, db, app wiring per target
```

The KMP JVM target is named `desktop`, so every module has `commonMain`,
`androidMain`, `desktopMain` (+ `commonTest`/`desktopTest`). `androidApp` and
`desktopApp` are thin entrypoints that launch the shared Compose UI.

## Modules and package map

### `core` — `dev.npn.core.*`

All domain logic, platform-agnostic. `commonMain` holds the logic; the platform
source sets hold implementations. Key packages:

| Package | Contents |
|---|---|
| `core.app` | Public session API and orchestrators, one contract file per concept: `LibrarySession.kt` (the aggregate of facets), `Documents.kt` (`DocumentRepository`, `TagRepository`, `SavedViewRepository`), `Metadata.kt` (`MetadataWriter`, `TagAdmin`, `DocLifecycle`), `FieldRegistry.kt` (the library's field definitions, values vocabulary and usage), `Ingest.kt`, `Ocr.kt`, `ReviewConfigFacade.kt`, `Maintenance.kt`, `WorkFacade.kt`, `DocContentFacade.kt`, `Filters.kt`, `Settings.kt`. Consumers depend on the facets they use, not on `LibrarySession`. `LibrarySessionImpl` coordinates imports/indexing and is the main entry point. `JournalCoordinator` is the only sanctioned path for writing journal events, enforced at the type level: the writers it wraps are `internal` to `core` and its `writer` is not public. |
| `core.journal` | The metadata journal: content-addressed JSONL transactions (`JournalEvent`, `JournalOp`), segments (`Segment`, `SegmentWriter`), codec (`JournalCodec`), HLC (`Hlc`), snapshot compaction (`SnapshotCompactor`, `SnapshotFormat`), and the HLC/OR-set fold (`FoldedState`, `FoldTest`). |
| `core.fs` | Filesystem abstraction over the library folder: `LibraryFileSystem` (platform impl), `NpnPaths` (`.npn/` layout, path classification, and the path-safety gate: every path reaches disk through `NpnPaths.normalize`, which refuses traversal; observation-side predicates use `isSafe` to ignore rather than throw), `ContentIdentity`/`HexEncoding` (hash handling), `OcrSidecarFiles`, `TrashOps`. |
| `core.index` | The rebuildable SQLite cache and maintenance: `Indexer`, `ImportService`, `MaintenanceService`, `RelinkService`, `SplitService`, `MetadataAccumulator`, `FtsWriteGate`, `DbLocalJournalState`, `DeviceIdentity`, `ScanReport`. |
| `core.db` | Room entities and DAOs (`NpnDatabase`, `DocumentDao`, `TagDao`, `FtsDao`, `MetadataProjectionDao`, `MiscDaos`, `Projections`). Schema files in `core/schemas`. Platform `NpnDatabase.android/desktop.kt` build the instance. |
| `core.pdf` | PDF rendering/thumbnails: `PdfEngine` (platform impl), `RenderGate`, `ThumbnailService`. |
| `core.ocr` | OCR pipeline and sidecars: `OcrEngine` (the engine interface; `MlKitOcrEngine` on android, `TesseractCliEngine` on desktop, `core.ai.AiOcrEngine` for a vision model), `OcrEngines` (the engine vocabulary + ranking), `OcrEngineSelector` (which engine a document's run may use — the one place that decides whether the *paid* engine runs), `OcrPipeline`, `OcrSidecar`. |
| `core.work` | `QueueDrainer`: the single-flight background drain shared by the OCR queue and the automatic AI enrichment queue. |
| `core.xmp` | XMP metadata on export/bake-in-place: `PdfXmpIO` (platform), `XmpBytes`, `XmpParser`, `XmpSerializer`, `XmpMerger`, `NpnXmpData`, `BakeService`. |
| `core.model` | `Document` domain model and the field model: `Field.kt` (`FieldType`, `FieldDef`, `FieldKeys` — well-known keys, seed definitions, key normalization) and `FieldValues.kt` (the one authority on a value's canonical form per type). |
| `core.rules` / `core.pathrules` | Auto-match rules (`RuleMatcher`, `RuleApplier`, `AutoMatchRule`) and storage-path templates (`StoragePathRule*`, `StoragePathContext`). |
| `core.watch` | Watched inbox folder: `WatchedFolder` (platform), `WatchedFolderImporter`. |
| `core.ai` | Local/AI chat loop and tools over the library (`KoogChatAgent`, `AiChatTool`, `AiFacadeImpl`, `LibraryAiTools`). Contracts split per concept: `AiChat.kt`, `AiSuggest.kt`, `AiOcr.kt`, `AiConfig.kt`; `AiFacadeImpl` implements all three facets, so they share one cached endpoint client. Consumers in `core.app` drive it: `AiEditRun` (bulk, review-then-apply), `AiAutoEnricher` (unattended, queued in `ai_enrich_queue` off the OCR thread), and `OcrPipeline` via the `AiOcrEngine` adapter (page-image transcription, one call per page). `LibraryAiTools` is the assistant's entire view of the library — see docs/AI_TOOL_DATA_GAPS.md for what it deliberately does not expose. |

Platform impls live in the same package under `androidMain`/`desktopMain` (e.g.
`core.fs.LibraryFileSystem`, `core.pdf.PdfEngine`, `core.ocr.OcrEngine`,
`core.db.NpnDatabase`).

### `composeApp` — `dev.npn.app.*`

Shared Compose Multiplatform UI + viewmodels + dependency wiring.

| Path | Contents |
|---|---|
| `app/App.kt` | Root composable / app shell. |
| `app/di/` | `AppEnvironment` (session lifecycle: open/close/swap, watched folder) + `DefaultAppEnvironment`. `PlatformLibraryFactory`/`WatchedFolderFactory` are fun interfaces the platform source sets implement; `AppEnvironment.android/desktop.kt` build the real per-target pieces. |
| `app/platform/` | Small shared platform hooks. |
| `app/ui/` | Screens, one package each: `library/` (list/grid, filters, preview), `detail/` (page viewer, metadata panel, history, split), `tags/`, `rules/`, `settings/`, `trash/`, `queue/` (the read-only OCR + AI enrichment backlog; a top-level rail destination on wide layouts, reached from settings on compact ones), `onboarding/`, `nav/` (shell + routes), `ai/`, `common/` (shared components: `TagEditor`, `DocThumbnail`, `SearchField`, `MessagesViewModel`, `SnackbarMessages`, `UiText`), `theme/`. ViewModels are co-located with their screen (e.g. `LibraryViewModel`, `DocDetailViewModel`). |
| `commonMain/composeResources` | `values/` + `values-nl/` string resources (localization checked by `checkLocalization`). |

### `androidApp` — `dev.npn.*`

Android entrypoint (`MainActivity`, `NpnApplication`), plus Android-only features:
`MlKitDocumentScanner`, `IntentImport` (share-sheet import), `NpnWork` (WorkManager
background OCR).

### `desktopApp` — `dev.npn.desktop`

Desktop entrypoint (`Main.kt`) and packaging (deb/AppImage/uber-jar). Hot Reload
via `mise hot`.

## Data flow

1. **Import**: `ImportService` (via `LibrarySessionImpl`) reads PDFs from picker /
   scanner / share intent / watched folder, hashes bytes (`SHA-256` = document
   identity), dedups, moves the file into the library, writes OCR sidecars, and
   appends metadata to the journal.
2. **Journal is source of truth for metadata**: every change (tags, field values,
    field definitions, system properties, settings, trash, relink) is an immutable,
    content-addressed JSONL transaction written by
    `JournalCoordinator`. You cannot write to the journal directly: the underlying
    `SegmentWriter`/`ContentAddressedJournalWriter` and the coordinator's held
    `writer` are `internal` to `core`, so the coordinator is the only reachable
    write path (enforced at the type level, not by convention).
3. **Index is a cache**: `Indexer` folds journal state (OR-set tags, HLC LWW) into
   the Room database (`FoldedState`, `MetadataAccumulator`). Field values land in
   `document_fields` (one row per document × key, canonical string plus a numeric
   shadow for NUMBER/DECIMAL) and definitions in `field_defs`; the five typed
   columns on `documents` are written from the same map as a read cache for list
   queries. One FTS `fields` column indexes every value. The db lives
   device-local, outside the library folder, and is rebuildable from the folder.
   The projection is a pure function of the applied source set, so
   `MetadataAccumulator` fingerprints that set and records it alongside the ready
   marker: opening a library whose fingerprint still matches reuses the stored
   projection instead of refolding, and `JournalCoordinator` leaves the
   `FoldedState` unmaterialized until a write or rescan actually needs it.
   Because of that reuse, **changing what a fold produces from the same events
   requires bumping `MetadataAccumulator.PROJECTION_VERSION`** — otherwise
   existing installs keep serving rows built by the old fold. See the KDoc there.
4. **UI reads the cache**: viewmodels subscribe to Room DAO Flows (with the named
   `WhileSubscribed(5_000)` constant) and map rows into UI models via the
   `core.app` facet interfaces; the Compose layer is a pure renderer of those.
5. **Platform boundaries**: every engine (`PdfEngine`, `OcrEngine`,
   `LibraryFileSystem`, Room db, `WatchedFolder`) has a common interface with
   per-target implementations wired in `AppEnvironment.android/desktop.kt`.
   `OcrEngine` is the exception that proves the rule: its implementations are not all
   platform-specific — the vision engine (`core.ai.AiOcrEngine`) lives in `commonMain`
   and is picked per document rather than per target.

## Where to look for common tasks

- A new library-wide synced setting → a `setting-set` op plus its key in
  `SyncedSettings.SEEDED_KEYS` (a private class at the top of `JournalCoordinator.kt`;
  read through `JournalCoordinator.settingFlow(key)`, written with
  `PendingOp.settingSet` — `reviewMode` is the model). A key left out of that list reads as
  null on open (the open path folds only the seeded settings, not the whole
  journal) and only appears once something writes it in the running session.
- A new document field is **data, not code**: define it in Settings → Documents (or
  the panel's "New field…"), which writes a `field-def`; every editor, filter, sort,
  storage-path placeholder (`{field:key}`), auto-match action, XMP bag entry, AI
  request and assistant tool follows the registry. A new field *type* is the
  two-file change: `FieldValues` (canonical form) + `FieldEditor` (control), plus
  the `aiLiteral` on the enum constant.
- A new per-document *system* marker (something the app owns, not the user) → a
  `SystemProperty` + `prop-set` event + `FoldedState` + `Projections` + the matching
  `core.app` contract file.
- New screen → package under `app/ui/`, viewmodel + screen in `commonMain`,
  route in `app/ui/nav/Route.kt`.
- New value or action in the document metadata panel → a field on `MetadataUiState`
  (assembled once in `DocDetailViewModel.metadata`) or on one of the four action
  holders in `MetadataPanelState.kt`. Both call sites (detail screen, library preview
  panel) then pick it up for free; they pass only a preview tab and a modifier.
- New platform behavior → interface in `core` commonMain, impl in each platform
  source set, wire in `AppEnvironment.*`.
- Background work → `core.watch` importer loops, `NpnWork` (Android), and the
  `core.work.QueueDrainer` instances in `LibrarySessionImpl` (OCR, AI enrichment,
  queued AI edit runs), exposed to callers that must await them as `session.work`
  (`WorkFacade`). A new kind of deferrable work means a queue table + a drain step
  + a kick, not a `launch` at the point of discovery.
- A queue row records only that work is *owed*, never that it is under way. No queue
  table has a `running` state: the drain is single-flight in-process, so a row left
  claiming to be running has nobody alive to withdraw the claim, and every drain
  thereafter skips it (`OcrQueueEntity` documents the outage this caused). "Who is
  being worked on right now" is a `StateFlow` on the pipeline (`OcrPipeline.inFlight`),
  which the read seam and `OcrStatus` combine in. An interrupted pass must leave its
  row exactly as it found it — including its attempt count, which is why cancellation
  is rethrown rather than recorded as a failure.
- Showing background work to the user → `WorkQueueReader` joins the queue tables to
  document names behind `WorkFacade.queue`, and derives whole-run progress behind
  `WorkFacade.editRuns`; a new queue table joins the same read there so the
  processing queue view keeps showing everything the app still owes.
- A new starting point for a bulk AI edit → an `EditTask` constant plus its `preset` in
  `app/ui/ai/AiEditTask.kt`, which is presentation policy composed out of the run's existing
  knobs (scope, tag flags, per-field `AiFieldMode`). Nothing in `core` knows about tasks: the
  page still builds one ordinary `AiEditRequest`. Editing what a task stands for demotes the run
  to `EditTask.CUSTOM`, so the label never describes a configuration the user edited away.
- Why the AI-edit page refuses to start → `AiEditUiState.startBlocker`, the one place that
  answers it, rendered by `AiEditRunBar`. A disabled Start must always be able to say why.
- Deferrable work the user must come back to → `AiEditQueue` is the pattern:
  persist the request and its subjects, buy the expensive part on a drain, store
  the answer verbatim, and surface it on the processing queue as something to open.
  Work that writes unattended (`AiAutoEnricher`) is the other pattern; pick by
  whether a human must confirm the result.
- Changing when the paid OCR engine runs → `OcrEngineSelector`, not `OcrPipeline`. It is the
  single place that answers "may this document use the vision model", and the reason the
  pipeline's "run the best-ranked engine" instinct cannot quietly bill a whole library. Its
  page threshold binds the *unattended* path only; an asked-for run
  (`OcrFacade.ocrWithAi`) runs at any length, and `OcrFacade.aiOcrOffer` is what warns the
  user first.
- Follow-up work after OCR → `OcrPipeline.onTextIndexed` fires whenever text is indexed,
  including on every index rebuild, so hang free/idempotent work there (auto-match rules).
  `OcrPipeline.onOcrProduced` fires only when this device just wrote a sidecar; anything
  that costs money or is externally visible belongs there (automatic AI enrichment).
