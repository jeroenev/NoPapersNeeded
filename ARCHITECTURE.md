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
`androidMain`, `desktopMain` (+ `commonTest`/`desktopTest`). `core` also has
`jvmSharedMain`, shared by the Android and desktop targets, for java.nio code both run:
the direct-filesystem `JvmLibraryFileSystem` and `JvmWatchedFolder`. `androidApp` and
`desktopApp` are thin entrypoints that launch the shared Compose UI.

Android picks the library implementation from the persisted location string
(`LibraryLocations`): a plain path opens the shared direct filesystem (the default, and
what Syncthing interoperability relies on; needs the all-files grant), a `content://`
tree uri opens `SafLibraryFileSystem` (the advanced option for cloud document providers
and for pre-existing SAF libraries).

## Modules and package map

### `core` — `dev.npn.core.*`

All domain logic, platform-agnostic. `commonMain` holds the logic; the platform
source sets hold implementations. Key packages:

| Package | Contents |
|---|---|
| `core.app` | Public session API and orchestrators, one contract file per concept: `LibrarySession.kt` (the aggregate of facets), `Documents.kt` (`DocumentRepository`, `TagRepository`, `SavedViewRepository`), `Metadata.kt` (`MetadataWriter`, `TagAdmin`, `DocLifecycle`), `FieldRegistry.kt` (the library's field definitions, values vocabulary and usage), `Ingest.kt`, `Ocr.kt`, `ReviewConfigFacade.kt`, `Maintenance.kt`, `WorkFacade.kt`, `DocContentFacade.kt`, `Filters.kt`, `Settings.kt`. Consumers depend on the facets they use, not on `LibrarySession`. `LibrarySessionImpl` coordinates imports/indexing and is the main entry point. `JournalCoordinator` is the only sanctioned path for writing journal events, enforced at the type level: the writers it wraps are `internal` to `core` and its `writer` is not public. |
| `core.journal` | The metadata journal: content-addressed JSONL transactions (`JournalEvent`, `JournalOp`), segments (`Segment`, `SegmentWriter`), codec (`JournalCodec`), HLC (`Hlc`), snapshot compaction (`SnapshotCompactor`, `SnapshotFormat`), and the HLC/OR-set fold (`FoldedState`; its convergence is property-tested by `FoldConvergencePropertyTest`). |
| `core.fs` | Filesystem abstraction over the library folder: `LibraryFileSystem` (platform impl), `NpnPaths` (`.npn/` layout, path classification, and the path-safety gate: every path reaches disk through `NpnPaths.normalize`, which refuses traversal; observation-side predicates use `isSafe` to ignore rather than throw), `ContentIdentity`/`HexEncoding` (hash handling), `OcrSidecarFiles`, `TrashOps`. |
| `core.index` | The rebuildable SQLite cache and maintenance: `Indexer`, `ImportService`, `MaintenanceService`, `RelinkService`, `SplitService`, `MetadataAccumulator`, `FtsWriteGate`, `DbLocalJournalState`, `DeviceIdentity`, `ScanReport`. |
| `core.db` | Room entities and DAOs (`NpnDatabase`, `DocumentDao`, `TagDao`, `FtsDao`, `MetadataProjectionDao`, `MiscDaos`, `Projections`). Schema files in `core/schemas`. Platform `NpnDatabase.android/desktop.kt` build the instance. |
| `core.pdf` | PDF rendering/thumbnails: `PdfEngine` (platform impl), `RenderGate`, `ThumbnailService`. |
| `core.ocr` | OCR pipeline and sidecars: `OcrEngine` (the engine interface; `MlKitOcrEngine` on android, `TesseractCliEngine` on desktop, `core.ai.AiOcrEngine` for a vision model), `OcrEngines` (the engine vocabulary + ranking), `OcrEngineSelector` (which engine a document's run may use, given `core.ai.UnattendedAiPolicy`'s permission to spend), `OcrPipeline`, `OcrSidecar`. |
| `core.util` | Shared primitives every layer uses: `runCatchingCancellable` (a `runCatching` that rethrows cancellation; use it, never `runCatching`, around suspend code) and `Throwable.reason()` (the message, else the class name). `NpnLog`, carried on `SessionDeps.log`, is where work nobody awaits reports a failure, through `runLogged("what") { }`: `AndroidLog` writes to logcat, desktop's `JvmLog` to stderr plus a file rolled over at 1 MB (the app's one `DesktopLog`, `npn.log` in the app directory under the platform data home). `NpnJson` holds the JSON configurations: `Persisted` for anything the app writes and reads back (tolerates other builds' records), `Lenient` for model output and third-party responses; only the journal codec keeps its own, pinned. |
| `core.work` | `QueueDrainer`: the single-flight background drain shared by the OCR queue, the automatic AI enrichment queue and queued AI edit runs. One worker loop fed by conflated kicks, so a burst of kicks during a drain costs one more drain; a step that throws is logged and ends that drain, never the loop. `backoffMs`: the retry ramp every persisted queue's failed rows wait out (`nextAttemptAt`). |
| `core.xmp` | XMP metadata on export/bake-in-place: `PdfXmpIO` (platform), `XmpBytes`, `XmpParser`, `XmpSerializer`, `XmpMerger`, `NpnXmpData`, `BakeService`. |
| `core.model` | Per-document vocabulary in `Document.kt` (`DocId`, `ReviewMode`, the app-owned `SystemProperty` keys) and the field model: `Field.kt` (`FieldType`, `FieldDef`, `FieldKeys` — well-known keys, seed definitions, key normalization) and `FieldValues.kt` (the one authority on a value's canonical form per type). |
| `core.rules` / `core.pathrules` | Auto-match rules (`RuleMatcher`, `RuleApplier`, `AutoMatchRule`) and storage-path templates (`StoragePathRule*`, `StoragePathContext`). |
| `core.watch` | Watched inbox folder: `WatchedFolder` (platform), `WatchedFolderImporter`. |
| `core.ai` | Local/AI chat loop and tools over the library (`KoogChatAgent`, `AiChatTool`, `AiFacadeImpl`, `LibraryAiTools`); `ChatHistoryWindow` sizes what a conversation keeps and what each request replays, in characters, with old tool results stubbed. Contracts split per concept: `AiChat.kt`, `AiSuggest.kt`, `AiOcr.kt`, `AiConfig.kt`, `UnattendedAiPolicy.kt` (may this replica spend without being asked); `AiFacadeImpl` implements the three facets, so they share one cached endpoint client. That client is built in one place, `AiRuntime.build(config, transport)`: a host that needs to see or shape every endpoint call (metering, logging, a test recorder) installs Ktor plugins on the base `transport` it passes to `AiFacadeImpl`; provider-specific wire needs (the OpenCode Go session header) are layered on top, never in place of it. Consumers in `core.app` drive it: `AiEditRun` (bulk, review-then-apply), `AiAutoEnricher` (unattended, queued in `ai_enrich_queue` off the OCR thread), and `OcrPipeline` via the `AiOcrEngine` adapter (page-image transcription, one call per page). `LibraryAiTools` is the assistant's entire view of the library — see docs/AI_TOOL_DATA_GAPS.md for what it deliberately does not expose. |

Platform impls live in the same package under `androidMain`/`desktopMain` (e.g.
`core.fs.LibraryFileSystem`, `core.pdf.PdfEngine`, `core.ocr.OcrEngine`,
`core.db.NpnDatabase`).

Every `core.app` facet keeps one contract, so a caller needs no defensive wrapping:

- **Main-safe.** A viewmodel may call a facet from the main thread. `JournalCoordinator` runs every
  fold and journal write on its own dispatcher; the filesystem, Room and PDF engines switch for
  their own work.
- **Expected failures are typed outcomes.** What normal use can run into — a file that cannot be
  imported, a split or bake that cannot happen, AI OCR without an endpoint — comes back as an
  outcome carrying an error enum (`ImportFailure`, `SplitFailure`, `BakeFailure`, `AiOcrOutcome`),
  with the cause in the session log. `composeApp` maps each enum to a string resource: no English
  text built in `core` is shown to the user. `DocContentFacade`'s nullable reads return null for
  anything they cannot show.
- **Bugs and misuse throw**, a closed session included. A viewmodel runs its writes through
  `launchReporting`, which turns a throw into a message instead of a crash.
- **Cancellation is always rethrown** (`runCatchingCancellable`), never reported as a failure.

Opening a library is `LibrarySessionImpl.openSession(SessionDeps)` (commonMain) followed by
`start()`. Everything the session runs — background loops, queue drains, every facet write — is a
child of one `SessionLifetime` job under `SessionDeps.scope`, so `close()` stops and awaits all of
it before the host closes the database; closing is final. On a plain JVM the `SessionDeps` come from `core.app.jvmSessionDeps`
(`desktopMain`): the one place that wires java.nio + PDFBox + Tesseract + a Room index under an
`AppDirs`. The desktop app, the `AppSessionRig` test harness and any headless JVM host build
their sessions through it — nothing in `composeApp` is needed to run a library without a UI.

### `composeApp` — `dev.npn.app.*`

Shared Compose Multiplatform UI + viewmodels + dependency wiring.

| Path | Contents |
|---|---|
| `app/App.kt` | Root composable / app shell. |
| `app/di/` | `AppEnvironment` (session lifecycle: open/close/swap, watched folder; a failed swap closes what it built and reopens the previous library) + `DefaultAppEnvironment`. `PlatformLibraryFactory`/`WatchedFolderFactory` are fun interfaces the platform source sets implement; `AppEnvironment.android/desktop.kt` build the real per-target pieces. On desktop the per-library wiring itself is `core`'s `jvmSessionDeps` (see below); the DI file only binds settings to the session's knobs. Android's factory dispatches on the location string: direct path → `JvmLibraryFileSystem` with `ContentResolverExternalIo` and exclusive `renameat2(RENAME_NOREPLACE)` publication through JNA, `content://` → `SafLibraryFileSystem`. |
| `app/platform/` | Small shared platform hooks. |
| `app/ui/` | Screens, one package each: `library/` (list/grid, filters, preview), `detail/` (page viewer, metadata panel, history, split), `tags/`, `rules/`, `settings/`, `trash/`, `queue/` (the read-only OCR + AI enrichment backlog; a top-level rail destination on wide layouts, reached from settings on compact ones), `onboarding/`, `nav/` (shell + routes), `ai/`, `common/` (shared components: `TagEditor`, `DocThumbnail`, `SearchField`, `MessagesViewModel`, `SnackbarMessages`, `UiText`), `theme/`. ViewModels are co-located with their screen (e.g. `LibraryViewModel`, `DocDetailViewModel`). One-shot messages have one path: a `MessagesViewModel` sends `UiMessage`s (text, plus an optional action such as Undo) into a buffered channel with `message` or `launchReporting`, and the screen's one `SnackbarMessages` shows them one at a time, in order. |
| `commonMain/composeResources` | `values/` (English) plus `values-nl/`, `values-de/`, `values-fr/`, `values-es/` and `values-pt/` string resources; `checkLocalization` checks every translation for missing keys and placeholder parity. |

### `androidApp` — `dev.npn.*`

Android entrypoint (`MainActivity`, `NpnApplication`), plus Android-only features:
`MlKitDocumentScanner`, `IntentImport` (share-sheet import), `NpnWork` (WorkManager
background OCR).

### `desktopApp` — `dev.npn.desktop`

Desktop entrypoint (`Main.kt`) and packaging. One instance runs per user: `SingleInstance` holds a
lock file in the app data directory, and a second launch only says so and exits. Closing the window
closes the library session (bounded by a timeout) before the process exits. Packaging: deb, rpm,
AppImage and uber-jar on Linux, a Flatpak bundle (manifest in `desktopApp/flatpak/`), and
`.msi`/`.exe` installers on Windows. Hot Reload via `mise hot`.

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
    Explicit PDF renames/moves and storage-path rules also write the `storagePath`
    system property, keyed by document hash, before moving bytes. HLC LWW chooses
    the intended location across devices; `DocumentPathReconciler` materializes it
    on metadata refresh or rescan. The cached `relPath` remains the actual available
    location, not an unfulfilled intent. Trash/delete suspend path reconciliation;
    restore records its final destination. PDFs without a path intent retain their
    discovered location.
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
   A rescan only hashes a PDF once its bytes have settled (`WriteSettle`): files
   written in place (`cp`, scanner apps) are deferred and picked up by a follow-up
   rescan, and `OcrPipeline` re-checks the hash of the bytes it reads before
   publishing a sidecar, so a partial file never gets a document identity.
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
- Changing *who may spend on AI unattended* → `core.ai.UnattendedAiPolicy`, the one object that
  answers it for both paid unattended paths (automatic vision OCR and automatic enrichment).
  `SessionDeps.unattendedAi` defaults to the device policy (the user's own `AiConfig` toggles);
  a host acting on a library's behalf supplies its own. Never read `AiConfig.ocrAuto` /
  `autoOnOcr` from a consumer directly.
- Changing when the paid OCR engine runs *for a given document* → `OcrEngineSelector`, not
  `OcrPipeline`. It layers the per-document rule (engine pin, page threshold) on top of the
  policy's yes/no, and is the reason the pipeline's "run the best-ranked engine" instinct cannot
  quietly bill a whole library. Its page threshold binds the *unattended* path only; an
  asked-for run (`OcrFacade.ocrWithAi`) runs at any length, and `OcrFacade.aiOcrOffer` is what
  warns the user first. "Asked for" means asked on this device: `ocrWithAi` writes the engine pin,
  which syncs and only selects which version is searched, plus `ocr_queue.aiRequested`, the local
  request to pay. An `ai` pin synced from another replica selects that replica's sidecar, or runs
  the model here only as unattended work (policy plus threshold).
- Follow-up work after OCR → `OcrPipeline.onTextIndexed` fires whenever text is indexed,
  including on every index rebuild, so hang free/idempotent work there (auto-match rules).
  `OcrPipeline.onOcrProduced` fires only when this device just wrote a sidecar; anything
  that costs money or is externally visible belongs there (automatic AI enrichment).
