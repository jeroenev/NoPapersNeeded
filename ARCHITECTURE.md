# Architecture

How the codebase is organized and how data flows through it. Read this first,
then AGENTS.md for build/test/commit conventions and the invariants you must not
break.

## Big picture

Local-first paperless document manager. A *library* is a plain folder of PDFs
plus a `.npn/` metadata directory; everything else is derived state that can be
rebuilt from the folder, except what one device alone knows (its journal identity,
its queues of work owed, chats), which it keeps in its own `device.db`. The app runs
on Android and Linux desktop from one code base:

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
the direct-filesystem `JvmLibraryFileSystem` and `JvmWatchedFolder`, and every `actual` the two
JVMs would otherwise write twice. `androidApp` and
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
| `core.app` | Public session API and orchestrators, one contract file per concept: `LibrarySession.kt` (the aggregate of facets), `Documents.kt` (`DocumentRepository`, `TagRepository`, `SavedViewRepository`), `History.kt` (a document's history as domain `HistoryEntry`s, so the UI never sees a journal op), `Metadata.kt` (`MetadataWriter`, `TagAdmin`, `DocLifecycle`), `FieldRegistry.kt` (the library's field definitions, values vocabulary and usage), `Ingest.kt`, `Ocr.kt`, `ReviewConfigFacade.kt`, `Maintenance.kt`, `WorkFacade.kt`, `DocContentFacade.kt`, `Filters.kt`, `Settings.kt`. Viewmodels take the `LibrarySession` and read the facets they use from it; code in `core` depends on the facets alone. The facets stay narrow interfaces so each has a small fake (`core-testing` holds the ones both modules' tests use). `LibrarySessionImpl` is the main entry point and holds only the session's lifecycle and ingest (imports, rescans, the folder watch, the periodic reconcile); the watch decides what each change calls for with the pure `watchAction` (one journal object is ingested at once, everything else waits for the folder to go quiet); `openSession` wires every other facet from the small builders in `SessionWiring.kt`; the three write facets are `OwnedWriter`, which runs each call on `MetadataWriterImpl` whole as work the session owns (`SessionLifetime.owned`). `OcrFacadeImpl` owns this device's OCR queue and what the user asks of OCR (re-runs, the AI engine, the engine pin), `MaintenanceFacadeImpl` duplicates, relinks and splits, `SessionWork` the queue drains (`WorkFacade`), and `JournalReviewConfig` (beside `MetadataWriterImpl`) the review workflow setting and its migration. `JournalCoordinator` is the only sanctioned path for writing journal events, enforced at the type level: `ContentAddressedJournalWriter` is `internal` to `core` and the coordinator holds its writer privately. |
| `core.journal` | The metadata journal: content-addressed JSONL transactions (`JournalEvent`, `JournalOp`, `PendingOp`) published as storage-v2 objects (`ContentAddressedJournalWriter`), the object-store layout under `.npn/journal/` and what each file there is (`JournalPaths`, `JournalPath`), the readers for v2 objects, snapshots and legacy v1 segments (`JournalSourceReaders`), codec (`JournalCodec`), HLC (`Hlc`, `HlcWatermarkAllocator`), snapshot compaction (`SnapshotCompactor`, `SnapshotFormat`), and the HLC/OR-set fold (`FoldedState`; its convergence is property-tested by `FoldConvergencePropertyTest`). See "Journal storage" below. |
| `core.fs` | Filesystem abstraction over the library folder: `LibraryFileSystem` (platform impl), `NpnPaths` (`.npn/` layout, path classification, and the path-safety gate: every path reaches disk through `NpnPaths.normalize`, which refuses traversal; observation-side predicates use `isSafe` to ignore rather than throw; `NpnPaths.Temp` names every temporary file the app writes into the library), `TempFileSweep` (a rescan deletes the temporary files a crash left behind once they are a day old, never a document's recovery file, nor a bake's temporary file unless the file at its document's path holds the same bytes), `ContentIdentity`/`HexEncoding` (hash handling), `OcrSidecarFiles` and `OcrSidecarName` (the one reader of a sidecar's file name and writer key, legacy forms included), `TrashOps`. |
| `core.index` | The rebuildable SQLite cache and maintenance: `Indexer`, `ImportService`, `MaintenanceService`, `RelinkService`, `SplitService`, `MetadataAccumulator`, `FtsRows`, `ScanReport`; and `DeviceIdentity`, this installation's device id for the library and the check that no other installation shares it (see "Journal storage"). |
| `core.db` | Room entities and DAOs for two databases per library. `NpnDatabase` (`index.db`) is the disposable index (one file per DAO, named after it: `DocumentDao`, `TagDao` with every `document_tags` query, `DocumentFieldDao`, `FtsDao`, `MetadataProjectionDao`, ...; the small result types in `Projections`; `LIVE_DOCUMENT` is the one SQL predicate for what the library shows); its builders fall back to a destructive migration, and anything a migration cannot rebuild must not live there. `DeviceDatabase` (`device.db`) is this device's own state, which nothing could rebuild: the `kv` device id (with the installation it belongs to) and chats, the OCR queue and paid-for OCR pages, the enrichment queue and its marks, queued AI edit runs. It has real migrations only, never a destructive fallback. `LegacyIndexTables` moves what older index schemas held into the journal or `device.db` on open. Schema files in `core/schemas/<database>/`. Platform `NpnDatabase.*.kt` / `DeviceDatabase.*.kt` build them. |
| `core.pdf` | PDF rendering/thumbnails: `PdfEngine` (platform impl), `RenderGate`, `ThumbnailService`. |
| `core.ocr` | OCR pipeline and sidecars: `OcrEngine` (the engine interface; `MlKitOcrEngine` on android, `TesseractCliEngine` on desktop, `core.ai.AiFacadeImpl.ocrEngine` for a vision model), `OcrEngineId` and `OcrState` (the engine and state vocabularies, the one source of their stored names; an engine's search rank is on the enum), `QueueState` (in `core.db`, beside the queue row), `OcrEngineSelector` (which engine a document's run may use, given `core.ai.UnattendedAiPolicy`'s permission to spend), `OcrPipeline`, `OcrVersions` (the sidecars stored per document, their writer keys, and which version is indexed), `OcrSidecar`. |
| `core.util` | Shared primitives every layer uses: `runCatchingCancellable` (a `runCatching` that rethrows cancellation; use it, never `runCatching`, around suspend code) and `Throwable.reason()` (the message, else the class name). `NpnLog`, carried on `SessionDeps.log`, is where work nobody awaits reports a failure, through `runLogged("what") { }`: `AndroidLog` writes to logcat, desktop's `JvmLog` to stderr plus a file rolled over at 1 MB (the app's one `DesktopLog`, `npn.log` in the app directory under the platform data home). `NpnJson` holds the JSON configurations: `Persisted` for anything the app writes and reads back (tolerates other builds' records), `Lenient` for model output and third-party responses; only the journal codec keeps its own, pinned. |
| `core.work` | `QueueDrainer`: the single-flight background drain shared by the OCR queue, the automatic AI enrichment queue and queued AI edit runs. One worker loop fed by conflated kicks, so a burst of kicks during a drain costs one more drain; a step that throws is logged and ends that drain, never the loop. `drainedQueue` builds a queue together with its drainer, handing the queue an `onEnqueued` callback that kicks that drainer. `QueueRetryPolicy`: when a persisted queue's failed row is tried again (the shared `backoffMs` ramp, stored as `nextAttemptAt`) and when it is given up on. `EndpointHealth`: the one cooldown of an AI endpoint, held by `AiFacadeImpl` and shared by the enrichment and edit queues, so a failed call in either holds both back and an answer to either lifts it; a write that fails after an answer is its row's own failure. |
| `core.xmp` | XMP metadata on export/bake-in-place: `PdfXmpIO` (platform, moves the raw packet), `XmpBytes`, `XmpSerializer`, `XmpParser` (reads back only what bake needs: title, document id, tags), `XmpMerger`, `NpnXmpData` with `XmpProperties` (every property name, and which fields get their own), `BakeService`. |
| `core.model` | Per-document vocabulary in `Document.kt` (`ReviewMode`, the app-owned `SystemProperty` keys) and the field model: `Field.kt` (`FieldType`, `FieldDef`, `FieldKeys` — well-known keys, seed definitions and their versioned upgrade, key normalization) and `FieldValues.kt` (the one authority on a value's canonical form per type); `AppLanguages` reduces an app-language tag to its subtag and knows each language's English name (for prompts) and Tesseract model (for automatic OCR). |
| `core.rules` / `core.pathrules` | Auto-match rules (`RuleMatcher`, `RuleApplier`, `AutoMatchRule`) and storage-path templates (`StoragePathRule*`, `StoragePathTemplate`, `StoragePathContext`). Both are library data: synced `setting-set` keys in the journal (`LibrarySettingKeys` in `core.app`), so every device files and matches documents alike; the rules table in the index only caches the journal's list (`AutoMatchRuleRepositoryImpl`). A device's pre-journal settings are adopted once by a library that has no value yet; the template and its switch are one setting, adopted together or not at all. A template that cannot expand says why with a `StoragePathError`, which Settings maps to its own copy; the context always carries the registry's field types, since they decide which fields take a date pattern. |
| `core.watch` | Watched inbox folder: `WatchedFolder` (platform), `WatchedFolderImporter`. Delete-after-import removes a source only while it still has the size and mtime it was listed with: a file written under its name since was never imported. |
| `core.ai` | Local/AI chat loop and tools over the library (`KoogChatAgent`, `AiFacadeImpl`, `LibraryAiTools`, whose `LibraryToolSet` holds the tools Koog advertises and a call map by tool name); `ChatHistoryWindow` sizes what a conversation keeps and what each request replays, in characters, with old tool results stubbed. Contracts split per concept: `AiChat.kt`, `AiSuggest.kt`, `AiConfig.kt` (with `AiAvailability`), `UnattendedAiPolicy.kt` (may this replica spend without being asked); `AiFacadeImpl` implements the two facets and is the vision OCR engine (`ocrEngine`), so all of them share one cached endpoint client (`AiRuntimeCache`). Every endpoint client is built in one place, `AiRuntime.build(config, transport)`: the facade's cached one, and the short-lived ones the settings self-test (`AiSelfTest`, which makes paid calls) and the model picker (`AiModelCatalog`) build per use. Its `transport` is the test seam: Ktor plugins installed on a base client a test passes in see every call that runtime makes; provider-specific wire needs (the OpenCode Go session header) are layered on top, never in place of it. Consumers in `core.app` drive it: `AiEditRun` (bulk, review-then-apply) and `AiEditQueue` (the same, handed to the background), which ask through one `SuggestionRunner` and so share its concurrency ceiling, `AiAutoEnricher` (unattended, queued in `ai_enrich_queue` off the OCR thread), and `OcrPipeline` through `AiFacadeImpl.ocrEngine` (page-image transcription, one call per page). `LibraryAiTools` is the assistant's entire view of the library — see docs/AI_TOOL_DATA_GAPS.md for what it deliberately does not expose. |

Platform impls live in the same package under `androidMain`/`desktopMain` (e.g.
`core.fs.LibraryFileSystem`, `core.pdf.PdfEngine`, `core.ocr.OcrEngine`,
`core.db.NpnDatabase`).

Every `core.app` facet keeps one contract, so a caller needs no defensive wrapping:

- **Main-safe.** A viewmodel may call a facet from the main thread. `JournalCoordinator` runs every
  fold and journal write on its own dispatcher; the filesystem, Room and PDF engines switch for
  their own work.
- **Expected failures are typed outcomes.** What normal use can run into — a file that cannot be
  imported, a split or bake that cannot happen, AI OCR without an endpoint, a deleted document's
  file that cannot be removed — comes back as an outcome carrying an error enum (`ImportFailure`,
  `SplitFailure`, `BakeFailure`, `AiOcrOutcome`, `PermanentDeleteOutcome`), with the cause in the
  session log. `composeApp` maps each enum to a string resource: no English text built in `core`
  is shown to the user. `DocContentFacade`'s nullable reads return null for anything they cannot
  show.
- **Bugs and misuse throw**, a closed session included. A viewmodel runs its writes through
  `launchReporting`, which turns a throw into a message instead of a crash.
- **Cancellation is always rethrown** (`runCatchingCancellable`), never reported as a failure.

Opening a library is `LibrarySessionImpl.openSession(SessionDeps)` (commonMain) followed by
`start()`. Everything the session runs — background loops, queue drains, every facet write — is a
child of one `SessionLifetime` job under `SessionDeps.scope`, so `close()` stops and awaits all of
it before the host closes the databases; closing is final. What an open writes to bring an existing
library up to this build (a newer field seed, this device's old settings and saved views) is best
effort, retried at the next open; only storage that cannot publish without replacement refuses the
library. On a plain JVM the `SessionDeps` come
from `core.app.jvmSessionDeps` (`desktopMain`): the one place that wires java.nio + PDFBox +
Tesseract + the two Room databases under an `AppDirs`. The desktop app, the `AppSessionRig` test
harness and any headless JVM host build their sessions through it — nothing in `composeApp` is
needed to run a library without a UI.
Android's counterpart is `core.app.androidSessionDeps` (`androidMain`). Both take the device
settings a session reads while it runs as one `SessionSettings`, which the apps build with
`SessionSettings.from(settingsRepository, systemLanguage)`; a new session setting is a field there.

### `composeApp` — `dev.npn.app.*`

Shared Compose Multiplatform UI + viewmodels + dependency wiring.

| Path | Contents |
|---|---|
| `app/App.kt`, `app/AppViewModel.kt` | Root composable / app shell, and the viewmodel that opens, restores and switches the library for it: one sealed `AppState` (`Restoring`, `Opening`, `NoLibrary`, `RestoreFailed`, `Open`) that also carries every failure to open, as a localized `OpenFailureReason` with the technical cause as an optional detail. |
| `app/di/` | `AppEnvironment` (session lifecycle: open/close/swap, watched folder; a failed swap closes what it built, reopens the previous library and rethrows) + `DefaultAppEnvironment`. A platform factory that knows why a library cannot open throws `LibraryUnavailableException` with an `OpenFailureReason`. The platform source sets hand it two plain functions, one that builds a location's `SessionDeps` and one that opens its watched folder, in `AppEnvironment.android/desktop.kt`. The per-library wiring itself is `core`'s `jvmSessionDeps`/`androidSessionDeps` (see above); the DI files only check that a location can be opened and bind the session's settings. Android's wiring dispatches on the location string: direct path → `JvmLibraryFileSystem` with `ContentResolverExternalIo` and exclusive `renameat2(RENAME_NOREPLACE)` publication through JNA, `content://` → `SafLibraryFileSystem`. The DI package imports no UI package. |
| `app/platform/` | Small shared platform hooks: `PlatformHelpers.kt`, the `expect` functions each platform answers (folder access and library-folder creation, image decoding, sharing, which settings it offers), plus the document scanner, foreground import and direct storage access. |
| `app/ui/` | Screens, one package each: `library/` (list/grid, filters, preview), `detail/` (page viewer, metadata panel, history, split; `DocumentView` is the one wiring of a document to its `DocDetailViewModel`, which the detail screen and the library's side panel each frame with their own chrome), `tags/`, `rules/`, `settings/`, `trash/`, `queue/` (the read-only OCR + AI enrichment backlog and the AI edit runs handed to the background; a top-level destination, on the rail on wide layouts and in the bottom bar's More sheet on compact ones), `onboarding/` (onboarding and the restore-failed screen, which share `LibraryLocationChooser`: the all-files grant, the folder pickers and the SAF switch), `nav/` (shell + routes), `ai/`, `common/` (shared components, one per UI concept: the form building blocks `SettingsSection`, `SwitchRow`, `HelpText`, `Dropdown`, `ExpanderRow` (with the `expander`/`disclosure` semantics every expander uses), `SuggestField` (the one autocomplete; `SearchField` shares its menu), `rememberNumberInput`, `UiLanguage` (the one language-name table); `BackTopBar` and `TooltipIconButton`/`TopBarActions` for top bars; `keyShortcuts`, the one way a screen binds keys (a `when (event.key)` naming each key's action); `EmptyState`, `TagEditor`, `TagFormDialog`, `DocThumbnail`; `MessagesViewModel`, `SnackbarMessages`, `UiText`; and `LocalWindowWidthClass`, the window's one wide-or-compact decision that the shell and every screen read), `theme/`. Leaf composables take state plus actions, never a viewmodel: a screen hands its viewmodel down as a small actions interface it implements (`AiEditSetupActions`, `ReviewController`, `SettingsActions`, `TagWrites`, `TrashWrites`, `DocumentWrites`, `DocumentActions`). ViewModels are co-located with their screen (e.g. `LibraryViewModel`, `DocDetailViewModel`). One-shot messages have one path: a `MessagesViewModel` sends `UiMessage`s (text, plus an optional action such as Undo) into an unbounded channel with `message` or `launchReporting`, where they wait for a collector however many pile up, and the screen's one `SnackbarMessages` shows them one at a time, in order. A message's `UiText` names a whole-sentence `StringResource` (or plural) and its arguments; the snackbar resolves it with the suspend `UiText.resolve(environment)` in the composition's resource environment, so it follows the app's language, and composables use the `@Composable` `resolve()`. |
| `commonMain/composeResources` | `values/` (English) plus `values-nl/`, `values-de/`, `values-fr/`, `values-es/` and `values-pt/` string resources; `checkLocalization` checks every translation for missing keys and placeholder parity. |

### `core-testing` — `dev.npn.core.testing`

Test doubles of `core`'s facets (documents, field registry, metadata writes, document content, AI
suggestions) and the `Doc`/`DocListItem` builders that feed them. `core`'s desktopTest and
`composeApp`'s commonTest depend on it, so both suites fake each interface the same way; nothing
else does, so it never ships.

### `androidApp` — `dev.npn.*`

Android entrypoint (`MainActivity`, `NpnApplication`), plus Android-only features:
`MlKitDocumentScanner`, `IntentImport` (share-sheet import, reported as soon as it is done),
`NpnWork` (WorkManager: the periodic watched-folder sweep, rescan and queue drains, and the
one-time drain of the OCR and enrichment a share import queued).

### `desktopApp` — `dev.npn.desktop`

Desktop entrypoint (`Main.kt`) and packaging. One instance runs per user: `SingleInstance` holds a
lock file in the app data directory, and a second launch only says so and exits. Closing the window
closes the library session (bounded by a timeout) before the process exits; `closeThenExit`
(`Shutdown.kt`) exits even when the close fails, and logs the failure. `DesktopBridge`
(composeApp) carries the window's two ties to the shared UI: the title the screens publish, which
the window collects as state, and files dropped on the window, which `AppViewModel` imports and
reports like any other import. Packaging: deb, rpm, AppImage and uber-jar on Linux, a Flatpak
bundle (manifest in `desktopApp/flatpak/`), and `.msi`/`.exe` installers on Windows. Every Linux
package renders its desktop entry from the one template in `desktopApp/linux/`; the task classes
live in `buildSrc`. Hot Reload via `mise hot`.

## Data flow

1. **Import**: `ImportService` (via `LibrarySessionImpl`) reads PDFs from picker /
   scanner / share intent / watched folder, hashes bytes (`SHA-256` = document
   identity), dedups, moves the file into the library, adds its index row and
   queues it for OCR. Only a live document whose file still holds the bytes makes
   an import a duplicate (whose source the watched folder may delete): the size and
   mtime its row stored prove that, as they do for a rescan, and only a file whose
   stat drifted is hashed, under the writer lock the check runs in; the bytes
   of a trashed, deleted or missing document bring that document back, metadata
   and all, and any copy of it left in the trash is deleted, under the writer lock
   and only while the document is still live (a trash of it since put its file
   there). Its only journal
   write is the restore of bytes the journal had trashed or deleted, in the step
   under the writer lock that makes the row live (a failed one takes the import
   back in that step), so no trash or rescan lands in between. Bytes a
   relink superseded (a bake's original) are no document of their own: an import
   of them is a duplicate of the document they became while the library shows
   it, and is refused otherwise; a rescan gives a file of them no row. `OcrPipeline`
   then writes the OCR sidecar, after which the auto-match rules (and, when
   enabled, AI enrichment) write metadata.
2. **Journal is source of truth for metadata**: every change (tags, field values,
    field definitions, system properties, library settings — review mode, storage-path
    rule, auto-match rules, saved views — trash, relink) is an immutable,
    content-addressed JSONL transaction written by
    `JournalCoordinator`. You cannot write to the journal directly: the underlying
    `ContentAddressedJournalWriter` is `internal` to `core` and the coordinator's
    held `writer` is private, so the coordinator is the only reachable write path
    (enforced at the type level, not by convention).
    Explicit PDF renames/moves and storage-path rules also write the `storagePath`
    system property, keyed by document hash, before moving bytes. HLC LWW chooses
    the intended location across devices; `DocumentPathReconciler` materializes it
    on metadata refresh or rescan. The cached `relPath` remains the actual available
    location, not an unfulfilled intent. Trash/delete suspend path reconciliation. A
    trash moves the file at the document's path only while it is the document's own
    (the size and mtime its row stored, else its hash, as an import judges it); a file
    written over it since the last rescan stays where it is, and the trash is recorded
    as for a document whose file is gone. Restore and delete for good take a trash
    entry for the document's only when it hashes to the document: the hash prefix in
    its name (`NpnPaths.trashPath`) only narrows the search (`TrashOps.copiesOf`).
    Trash, restore and delete for good read the row, check the file and move it under
    the writer lock, in one step with their journal write
    (`JournalCoordinator.changeLifecycle`), so no rescan or storage-path move lands in
    between: a restore lists and hashes the trash entries before it takes the lock, and
    under it checks them by a stat, hashing again only one whose stat changed (it lists
    the trash there only when no copy it found is left, or it found none: a copy can
    reach the trash after the listing); a delete for good acts on a document in the trash
    only (one restored meanwhile, here, on another device or by an import, is left alone),
    and deletes the bytes after its tombstone is published, hashed before the lock and
    deleted under it only while unchanged and while the journal still has the document
    deleted (an import may have brought it back meanwhile, which it records under the
    lock; a rescan showing a file still in place records no return so soon, and keeps
    nothing). A file it cannot remove leaves the document deleted and is reported
    (`PermanentDeleteOutcome.FILE_LEFT`). Restoring or emptying the whole trash
    (`DocLifecycle.restoreDocs`, `deleteDocsPermanently`) lists it once for all its
    documents.
    Restore records its final destination: the path the document was trashed from, or
    a `(restored)` name beside it when a file is there now or another live row names it
    (as for an import: a row whose file went outside the app names its path until a
    rescan finds it missing). PDFs without
    a path intent retain their discovered location. A path holds one row: a trashed,
    deleted or missing row gives it up to a live document and waits under
    `.npn/missing/` with its flags kept (`DocumentDao.upsertAll`, which fails the write
    rather than drop a row on a path another live row holds; and before a rename or
    storage-path move onto it, `DocumentPathReconciler`). Only a document whose own
    file the other bytes replaced goes missing and is offered for relink; a rescan
    judges trashed and deleted by the journal it folds as well as by the row, since
    another device's trash can arrive together with the file change.
3. **Index is a cache**: `Indexer` folds journal state (OR-set tags, HLC LWW) into
   the Room database (`FoldedState`, `MetadataAccumulator`). Field values land in
   `document_fields` (one row per document × key, canonical string plus a numeric
   shadow for NUMBER/DECIMAL) and definitions in `field_defs`; the five typed
   columns on `documents` are written from the same map as a read cache for list
   queries. A relink target (a bake's result) shows its predecessors' metadata as its own
   (`FoldedState.inheritedFields`, `inheritedProp`, `inheritedTags`): per field or property the
   latest write among them wins, a clear included, and their tags are one OR-set, so an edit, a
   clear or a tag removal on it reaches what it inherited, and an incremental fold projects it
   with its predecessors as a rescan does. One FTS `fields` column indexes every value. FTS5
   finds a row only by MATCH or
   rowid, so a document's FTS row is reached through `fts_keys`; the fold writes its metadata
   columns and `OcrPipeline` its extracted text, and neither rewrites the other's. Every fold
   writes in one transaction (`Indexer.projection`, which a commit's journal bookkeeping joins), so
   no reader sees a row before its tags and fields; moving a PDF to its intended path and recording
   where documents were seen is filesystem work, and runs after that transaction commits. The db
   lives device-local, outside the library folder, and is rebuildable from the folder: delete
   `index.db` and the next open refolds the journal and rescans. Its neighbour `device.db` holds
   what a rebuild could not bring back, keyed by document hash, so it survives one.
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
   publishing a sidecar, so a partial file never gets a document identity. A read
   that finds other bytes asks for that follow-up rescan and leaves its run queued,
   uncharged, until the file has settled; a second one in a row counts as a failure.
4. **UI reads the cache**: viewmodels subscribe to Room DAO Flows (with the named
   `WhileSubscribed(5_000)` constant) and map rows into UI models via the
   `core.app` facet interfaces; the Compose layer is a pure renderer of those.
5. **Platform boundaries**: every engine (`PdfEngine`, `OcrEngine`,
   `LibraryFileSystem`, Room db, `WatchedFolder`) has a common interface with
   per-target implementations wired in `jvmSessionDeps` / `androidSessionDeps` (the
   watched folder in `AppEnvironment.android/desktop.kt`).
   `OcrEngine` is the exception that proves the rule: its implementations are not all
   platform-specific — the vision engine (`core.ai.AiFacadeImpl.ocrEngine`) lives in `commonMain`
   and is picked per document rather than per target.

## Journal storage

- **Formats.** Every transaction is published as one storage-v2 object,
  `.npn/journal/objects-v2-<xx>/<sha256>.jsonl`, named by the SHA-256 of its bytes, with a fresh
  actor id per transaction; compaction folds old objects into snapshots
  (`objects-snap-v2-<xx>/`) and deletes them. An object a snapshot supersedes is deleted later
  (a delete that failed, a copy put back) only while that snapshot, or one that superseded it in
  turn, is on disk and the same reconcile pass has read it; what the index remembers of a snapshot
  vouches for nothing. The pass reads that snapshot instead of the object, so an object a sync
  conflict wrote over since fails nothing. Nor does a manifest vouch for more than it holds: those
  snapshots must hold every event the index recorded for the object. One that names an object
  without its events deletes nothing, and the index drops that claim, so the object is live again
  (its loss counts as one) until a compaction carries it. A source that vanished is a loss only while no listed file holds its
  events (`carriedByListed`); an unexplained one is tolerated for a grace window
  (`SourceSetValidator.PENDING_SUPERSESSION_LIMIT` reconciles, for a snapshot still on its way),
  then blocks writes. A snapshot gone with everything it superseded back on disk (a folder put
  back from before a compaction) lost nothing: the reconcile forgets it, as a rebuild would, and
  the next compaction replaces those objects again. Builds before storage-v2 wrote per-device v1 segments,
  `.npn/journal/<deviceId>/<seq>.jsonl`: those are still read and folded, never written. There is
  no fallback format.
- **Compatibility.** A build folds only the ops it knows. An object, snapshot or segment holding an
  op it does not know, or a known op it cannot read (a `prop-set` of a newer system property, a
  `field-def` of a newer field type), is refused there as unsupported: that build blocks writes and
  stops opening the library until it updates. There is no skip path, so a new op ships in a build
  that reads it before any build writes it; `WireFormatTest` pins the op names. A payload key a
  build does not know breaks less, but lasts only in the object that holds it: that build drops it
  from every event it writes again, compaction included, which re-encodes the events it carries
  from its own index. A build from before `perDocument` that compacts the library takes the flag
  off every field definition it carries.
- **Publication.** An object is staged under a `.part` name in its bucket, hash-checked, then
  published without replacement: a hard link, or where the filesystem has none (Android's shared
  storage, exFAT) an exclusive rename (`renameat2(RENAME_NOREPLACE)` on Linux and Android,
  `MoveFileExW` without `MOVEFILE_REPLACE_EXISTING` on Windows), or on SAF a rename that is checked
  by name and hash afterwards. A filesystem with neither primitive throws
  `VerifiedPublicationUnavailableException`, and the library refuses to open with its own message
  (`OpenFailureReason.UNSUPPORTED_STORAGE`) rather than writing anything.
- **Device identity.** `device.id` (in `device.db`) names what this device writes under its own
  name: OCR sidecars, and the watcher's filter of its own writes. Two installations must never share
  one, so `DeviceIdentity` ties it to the installation (`SessionDeps.installationId`, which Android
  keeps in the no-backup directory) twice, and either disagreeing gives a fresh id at open:
  `device.db` records which installation the id belongs to (Android restores `device.db`, so a
  restored or reinstalled app gets a new id and keeps its queued, paid-for work), and the library
  holds one write-once marker per installation that used the id, `.npn/devices/<deviceId>/<installationId>.json`
  (a second installation's marker means a clone; each moves off the id once it sees the other's).
  Publishing that marker uses the journal's primitive, and once it is there every open publishes
  and deletes a throwaway object under `.npn/tmp` the same way, which is how an unsupported
  filesystem is found out at every open, a remount since the last one included (SAF, whose
  provider publishes with its own rename, skips that probe: `LibraryFileSystem.publicationMayBeLost`).
  A fresh id costs nothing: the old id's sidecars are adopted as another device's.

- **Delete and restore.** `doc-trash`/`doc-restore` and `doc-delete`/`doc-undelete` are
  last-writer-wins pairs by HLC; an undelete also takes the document out of the trash. When the
  bytes of a deleted or trashed document come back, the return is recorded in the journal, or the
  next fold would hide the document again: an import of them writes it under the lock it placed
  the file under (`ImportJournal.restoreReturned`, handed to it by `JournalCoordinator.serialized`),
  and a rescan that finds the file back in the library
  writes it once the event that hid the document is a day old (`RESTORE_SETTLE_MS`), as the scan's
  last step; a write that fails is logged and left to the next rescan, never failing the scan or
  blocking writes. Sooner, the file may be one a sync tool has not taken away yet, so the rescan
  shows it for that scan only.
  The file wins only with the document's own bytes: the rescan must know the hash of the file at
  the document's path, or at the path it was trashed from when no other row holds that, and the
  hash must be the document's. Another file under the same name (a second `scan.pdf`) or a file
  still being written brings nothing back.
  `doc-undelete` is newer than the other lifecycle ops: a build from before it refuses an object
  holding one (see Compatibility), so it stops opening the library once a return is recorded.

## Where to look for common tasks

- A new library-wide synced setting → a `setting-set` op under a new key, read through
  `JournalCoordinator.settingFlow(key)` and written with `PendingOp.settingSet` (`reviewMode`
  is the model). The open path folds every setting the journal holds, and nothing else, so the
  key needs no registration anywhere.
- A new document field is **data, not code**: define it in Settings → Documents (or
  the panel's "New field…"), which writes a `field-def`; every editor, filter, sort,
  storage-path placeholder (`{field:key}`), auto-match action, XMP bag entry, AI
  request and assistant tool follows the registry. A new field *type* is the
  two-file change: `FieldValues` (canonical form) + `FieldEditor` (control), plus
  the capabilities its enum constant must declare (`aiLiteral`, `isTextual`, `isNumeric`,
  `isDate`, `singleLine`, `hasVocabulary`), which every other caller reads instead of
  comparing types.
- A new per-document *system* marker (something the app owns, not the user) → a
  `SystemProperty` + `prop-set` event + `FoldedState` + `Projections` + the matching
  `core.app` contract file.
- New persisted state → decide where it lives by who could rebuild it. Library data every
  device must agree on goes in the journal (a `setting-set` key or an op); anything the
  folder and journal can recreate goes in the index; what only this device knows and
  nothing could recreate (a request made here, work already paid for) goes in
  `DeviceDatabase`, with a real migration for every schema change.
- New screen → package under `app/ui/`, viewmodel + screen in `commonMain`,
  route in `app/ui/nav/Route.kt`.
- New setting → a control in its pane (`app/ui/settings/*Pane.kt`), built from the `ui/common`
  form blocks, and its label among its category's `keywords` in `SettingsCategory.kt`, so search
  finds it; `SettingsSearchCoverageTest` fails on a visible label search cannot find.
- New value or action in the document metadata panel → a field on `MetadataUiState`
  (assembled once in `DocDetailViewModel.metadata`), or a member of `DocumentWrites` (written
  at once, implemented by `DocDetailViewModel`) or `MetadataActions` (edits that wait in the
  document's draft, routed by `DocumentEditor`), both in `MetadataPanelState.kt`. A new document
  action (beside rename, split, bake) → a member of `DocumentActions` in `DocActions.kt`. Both
  hosts of `DocumentView` (detail screen, library preview panel) then pick it up for free.
- New platform behavior → interface in `core` commonMain, impl in each platform
  source set, wired per library in `jvmSessionDeps` / `androidSessionDeps`, or in
  `AppEnvironment.*` when it belongs to the app rather than to a library.
- Background work → `core.watch` importer loops, `NpnWork` (Android), and the
  `core.work.QueueDrainer` instances the session wires (OCR, AI enrichment,
  queued AI edit runs), exposed to callers that must await them as `session.work`
  (`WorkFacade`). A new kind of deferrable work means a queue table + a drain step,
  not a `launch` at the point of discovery, and the queue triggers its own drain:
  whatever records new work kicks that queue's drainer itself, so no caller has to
  remember to. The enrichment and edit queues are built with `drainedQueue`, whose
  `onEnqueued` callback is that kick; they deliberately have no table observer,
  which could not tell new work from a failed row being rescheduled. The OCR queue
  is owned by `OcrFacadeImpl`, which kicks on its own enqueues and watches the table
  for rows other writers add (an import, an upgrade). Beyond that the session only
  kicks every queue at start, to resume what a killed process left, and on its
  15-minute tick, which is what serves a backoff that has fallen due.
- A queue row records only that work is *owed*, never that it is under way. No queue
  table has a `running` state: the drain is single-flight in-process, so a row left
  claiming to be running has nobody alive to withdraw the claim, and every drain
  thereafter skips it (`OcrQueueEntity` says why). "Who is being worked on right now"
  is a `StateFlow` on the live queue (`OcrPipeline.inFlight`,
  `AiEditQueue.inFlight`), which the read seam and `OcrStatus` combine in; it is never
  inferred from a row's progress. An interrupted pass must leave its
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
- Changing *who may spend on AI unattended* → `core.ai.UnattendedAiPolicy`, the one class that
  answers it for both paid unattended paths (automatic vision OCR and automatic enrichment), from
  the user's own `AiConfig` toggles. Never read `AiConfig.ocrAuto` / `autoOnOcr` from a consumer
  directly.
- Changing when the paid OCR engine runs *for a given document* → `OcrEngineSelector`, not
  `OcrPipeline`. It layers the per-document rule (engine pin, page threshold) on top of the
  policy's yes/no, and is the reason the pipeline's "run the best-ranked engine" instinct cannot
  quietly bill a whole library. Its page threshold binds the *unattended* path only; an
  asked-for run (`OcrFacade.ocrWithAi`) runs at any length, and `OcrFacade.aiOcrOffer` is what
  warns the user first. "Asked for" means asked on this device: `ocrWithAi` writes the engine pin,
  which syncs and only selects which version is searched, plus `ocr_queue.aiRequested` in
  `device.db`, the local request to pay. An `ai` pin synced from another replica selects that
  replica's sidecar, or runs the model here only as unattended work (policy plus threshold).
- Follow-up work after OCR → `OcrPipeline.onTextIndexed` fires whenever text is indexed,
  including on every index rebuild, so hang free/idempotent work there (auto-match rules).
  `OcrPipeline.onOcrProduced` fires only when this device just wrote a sidecar; anything
  that costs money or is externally visible belongs there (automatic AI enrichment).
