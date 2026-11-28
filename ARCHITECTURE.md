# Architecture

How the codebase is organized, how data flows through it, and the rules each part keeps. Read
this first. The other documents each own one thing and this one does not repeat them:

| Document | Owns |
|---|---|
| AGENTS.md | Build and test commands, conventions, detekt, and the short list of invariants you must not break. |
| CONTEXT.md | The domain glossary: what Field, Edit Run, Review, facet and so on mean here. |
| DEVELOPMENT.md | Design background, packaging and release mechanics, the library folder layout on disk. |
| `docs/` | Longer notes on one topic each: `SIMULATION.md` (the deterministic simulation of the core), `MUTATION_TESTING.md` (reading a PIT report), `SYNC_QUALIFICATION.md` (the cloud sync scenario suite, provider matrix and restore walkthrough), `AI_TOOL_DATA_GAPS.md` (library data the assistant's tools do not expose). The directory is otherwise ignored by git, for local working notes; a document meant for the repository is listed in `.gitignore`'s exceptions. |

The document has four layers. Sections 1 to 3 are the map: what the system is and where the code
lives. Sections 4 and 5 are how it runs: lifecycle, concurrency, and the paths data takes. Section 6
is the reference: the rules each concept keeps, grouped by concept, with the class that enforces
each. Sections 7 to 10 cover the UI, the platform shells, the tests, and recipes for common tasks.

## 1. The system in one page

No Papers Needed is a local-first paperless document manager. A **library** is a plain folder of PDFs
plus a `.npn/` metadata directory. There is no server: devices share a library through whatever file
sync tool the user already runs, and every device merges what it finds.

Four kinds of state, and who owns each:

| Store | What it holds | Truth or cache | Where | Synced |
|---|---|---|---|---|
| The library folder | The PDFs. A document is identified by the SHA-256 of its bytes; its path is a property. | Truth | In the library | Yes |
| `.npn/journal/` | Every metadata change as an immutable, content-addressed transaction. Tags, fields, settings, trash, relinks. | Truth for metadata | In the library | Yes |
| `.npn/ocr/` and `.npn/devices/` | OCR sidecars keyed by document hash and writer; device-identity markers. Write-once. | Truth | In the library | Yes |
| `index.db` | The Room index: the journal folded onto the documents the folder holds, plus FTS. Disposable; a dropped index rebuilds from the folder. | Cache | Device-local, outside the library | Never |
| `device.db` | What only this device knows and nothing could rebuild: its journal device id, the journal actors it published under (history's "This device"), the OCR and AI queues, enrichment marks, chats, whether it shows reminder notifications and which it already showed, how far it read each mail folder, its AI spend ledger, and the library's cloud remote with what the cloud sync knows of it. Real migrations only. | Truth, device-owned | Device-local, beside the index | Never |
| Thumbnails | Rendered pages. | Cache | Device-local | Never |

Merge rules are fixed: tags are an OR-set; single-value fields, system properties, settings, field
definitions, trash/restore and delete/undelete are last-writer-wins by hybrid logical clock (HLC).
Everything in `.npn/` the app writes is write-once; nothing is ever appended to or rewritten.

The code is one Kotlin Multiplatform build with two platform shells:

```
androidApp ─┐
            ├─ composeApp   shared Compose UI + viewmodels + app wiring   (dev.npn.app.*)
desktopApp ─┘        │
                     ▼
                   core      domain, journal, index, fs, pdf, ocr, ai      (dev.npn.core.*)
                     │
                     └─ platform source sets implement fs, pdf, ocr, db and session wiring
```

`core` knows nothing about the UI; a headless JVM host can run a library with `core` alone.

## 2. Modules and source sets

| Module | Packages | Role |
|---|---|---|
| `core` | `dev.npn.core.*` | All domain logic and persistence. `commonMain` holds the logic; platform source sets hold implementations. |
| `core-testing` | `dev.npn.core.testing` | Fakes of `core`'s facets and the `Doc`/`DocListItem` builders, shared by the tests of `core` and `composeApp`. Only test source sets depend on it. |
| `composeApp` | `dev.npn.app.*` | Shared Compose Multiplatform UI, viewmodels, dependency wiring, string resources in six languages. |
| `androidApp` | `dev.npn` | Android entrypoint: `MainActivity`, `NpnApplication`, the ML Kit scanner, share-sheet import, WorkManager jobs. |
| `desktopApp` | `dev.npn.desktop` | Desktop entrypoint, single-instance lock, packaging. |
| `buildSrc` | | Build logic: convention plugins, packaging tasks, the localization and field-type guards. |
| `baselineprofile` | | Generates the Android baseline profile on a device; never in CI. |

The KMP modules name their JVM target `desktop`. Source sets:

- `commonMain`, `androidMain`, `desktopMain` in `core` and `composeApp`; `core-testing` has only
  `commonMain`.
- `core` also has `jvmSharedMain`, shared by the Android and desktop targets, for java.nio code both
  run: `JvmLibraryFileSystem`, `JvmWatchedFolder`, and every `actual` the two JVMs would otherwise
  write twice.
- Tests: `commonTest` compiles for both targets; `desktopTest` runs on the desktop JVM;
  `androidHostTest` runs the Android target on a host JVM against `android.jar` stubs;
  `androidDeviceTest` holds what needs a device.

Platform implementations live in the same package as their interface, under `androidMain` or
`desktopMain`: `core.fs.LibraryFileSystem`, `core.pdf.PdfEngine`, `core.ocr.OcrEngine`,
`core.db.NpnDatabase`. A library is always a plain path, on Android too: it opens the shared direct
filesystem (`JvmLibraryFileSystem`), which is what Syncthing interoperability relies on; a cloud
provider's folder is reached through cloud sync instead. Only a watched folder can be a
`content://` tree URI (`SafWatchedFolder`).

## 3. Core package map

One line per package. The rules each package enforces are in section 6.

| Package | Contents |
|---|---|
| `core.app` | The public session API and the orchestrators behind it. One contract file per concept: `LibrarySession.kt`, `Documents.kt`, `History.kt`, `Metadata.kt`, `FieldRegistry.kt`, `Ingest.kt`, `Ocr.kt`, `LibrarySettings.kt`, `Maintenance.kt`, `WorkFacade.kt`, `DocContentFacade.kt`, `Filters.kt`. Implementations: `LibrarySessionImpl`, `SessionGraph`, `SessionOpening.kt`, `MetadataWriterImpl`, `DocLifecycleImpl` (with its pure `LifecyclePlan`), `OcrFacadeImpl`, `MaintenanceFacadeImpl`, `SessionWork`, `JournalReviewConfig`, `BakeRelink`. |
| `core.app.aiedit` | The AI edit engine over the facets: `AiEditRun`, `AiEditQueue`, `AiAutoEnricher`, `AiEditReview.kt` (`ReviewProjector`, `ReviewApply`), `SuggestionRunner`. |
| `core.journal` | The metadata journal: `JournalEvent`, `JournalOp`, `PendingOp`; `ContentAddressedJournalWriter`; `JournalPaths`; the readers for v2 objects and snapshots (`JournalSourceReaders`); `JournalCodec`; `Hlc`, `HlcWatermarkAllocator`; `SnapshotFormat` (the compaction snapshots earlier builds wrote, read and never written); `EventCopies` (two copies of one event); the fold, `FoldedState`. |
| `core.index` | The rebuildable index and its maintenance. The executor: `JournalCoordinator`. The pure decisions it and its helpers carry out: `RescanDecision`, `FoldLifecycle`, `ImportDecision` (the row an import writes). The effects: `Indexer`, `MetadataAccumulator`, `FoldProjection`, `SourceSetValidator`, `ImportService`, `DuplicateResolver`, `SplitService`, `ScanReport`, `DeviceIdentity`, `DocumentPathReconciler`, `ExternalDocumentMoves`. |
| `core.db` | Room entities and DAOs for the two databases: `NpnDatabase` (`index.db`) and `DeviceDatabase` (`device.db`). One file per DAO. `LegacyIndexTables` moves what older index schemas held into the journal or `device.db`. |
| `core.fs` | `LibraryFileSystem` and its platform implementations; `NpnPaths` (the `.npn/` layout, path classification, the path-safety gate, every temporary file name); `TempFileSweep`; `ContentIdentity`, `HexEncoding`; `TrashOps`. |
| `core.model` | `Document.kt` (`ReviewMode`, `SystemProperty`), `Field.kt` (`FieldType`, `FieldDef`, `FieldKeys` with the seeds and their versioned upgrade), `FieldValues.kt` (canonical forms), `AppLanguages`. |
| `core.ocr` | `OcrEngine` and its implementations, `OcrEngineId`, `OcrState`, `OcrEngineSelector`, `OcrPipeline`, `OcrVersions`, `OcrSidecar`, `OcrSidecarFiles`, `OcrSidecarName`. |
| `core.pdf` | `PdfEngine`, `RenderGate`, `ThumbnailService`, `ThumbnailStore` (`DirThumbnailStore` on both JVMs). |
| `core.xmp` | XMP on export and bake-in-place: `PdfXmpIO`, `XmpBytes`, `XmpSerializer`, `XmpParser`, `XmpMerger`, `NpnXmpData`, `XmpProperties`, `BakeService`. |
| `core.rules`, `core.pathrules` | Auto-match rules (`RuleMatcher`, `RuleApplier`, `AutoMatchRule`, `AutoMatchRuleSource`) and storage-path templates (`StoragePathRule*`, `StoragePathTemplate`, `StoragePathContext`). |
| `core.watch` | Watched inbox folder: `WatchedFolder` (platform), `WatchedFolderImporter`. |
| `core.work` | `QueueDrainer`, `drainedQueue`, `QueueRetryPolicy`, `EndpointHealth`. |
| `core.ai` | The AI layer: `AiChat.kt`, `AiSuggest.kt`, `AiConfig.kt`, `UnattendedAiPolicy.kt`; `AiFacadeImpl`, `KoogChatAgent`, `LibraryAiTools`, `ChatHistoryWindow`, `AiRuntime`, `AiRuntimeCache`, `AiSelfTest`, `AiModelCatalog`, `ProviderClientFactory`. |
| `core.settings` | This device's own settings and directories, never per library: `Settings.kt` (`SettingsRepository`, `SettingsSnapshot`, `SettingsCodec`, `SettingsKeys`, `LegacyLibrarySettings`, `GridSizing`), `SessionSettings`, `AppDirs`, `LibraryLocations`, `PlatformDirs`. |
| `core.security` | The app lock: `AppLock` (the lock/unlock state machine, timeout and retry backoff), `AppLockSettings` and `LockCredential` (the salted hash a settings file holds), `LockCrypto` (PBKDF2-HMAC-SHA256, shared by the JVM targets). Access control over the app, never encryption of the library. |
| `core.sync` | Cloud sync of the library folder through a bundled rclone (section 5.6). `SyncTransport`/`RemoteStore` are the transfer seam; `RcloneTransport` (`jvmSharedMain`) owns one long-lived `rclone rcd` engine per launch configuration (`RcloneDaemon`: loopback RC, random credentials passed in its environment (never its command line), app-private config, a cancelled call closing its connection, final `close` plus a JVM shutdown hook; `RcloneCli` is the stateless `rc --loopback` fallback), so a pass never spawns a process per file and the browser sign-in can span calls. It also exposes `CloudSetup`, the in-app provider wizard: a curated catalog (`RcloneCatalog`) rendered from `config/providers` metadata, driven through rclone's non-interactive `config/create` state machine with `config/oauthstatus` for the sign-in, and `operations/list`/`operations/mkdir` for the folder picker, with no per-provider Kotlin. `SyncEngine` runs one pass: `DocumentResolver`, `SyncPlanner` (pure), `LocalApplier`, `RemotePusher`, `MetadataTransfer`. `SyncService` is the session's `SyncFacade`, and keeps the library's own `CloudSyncConfig` (remote, schedule) in its `device.db`, so each library on a device has a remote of its own; `SyncHooks` is what a pass asks of the session. Credentials stay in the app-private rclone config (Android: no-backup files), and every setup names its remote afresh (`<type>-<hex>`), which disconnecting deletes. Expected failures are typed (`SyncFailure`, `ConflictKind`, `EngineProblem`, `SetupFailure`) and mapped to strings in `composeApp`; rclone's own text travels only as a detail. Android ships the engine as `jniLibs/<abi>/librclone.so` (cross-compiled with Go and the NDK), desktop packages in their app resources, `rclone/` (`bundledRcloneBinary`). |
| `core.update` | The update check: `AppVersions`, `LatestReleaseSource`, `GitHubLatestRelease`. |
| `core.diagnostics` | The user-initiated diagnostics bundle: `DiagnosticsReport` (counts, platform, sync health, AI provider/model), `Diagnostics.render` and its redaction, and the prefilled issue URL. Never document content, names, paths, addresses or keys; the log tail is scrubbed. |
| `core.util` | `runCatchingCancellable`, `Throwable.reason()`, `EpochClock`, `NpnLog`, `runLogged`, `RotatingLogFile`, `NpnJson`. |

Why two things sit where they do:

- `JournalCoordinator` is in `core.index`, not `core.journal`, because it drives the index;
  `core.journal` stays free of the index.
- `core.app.aiedit` is a subpackage of `core.app`, not a sibling of `core.ai`, because it consumes
  the session's facets, and `core.ai` stays the endpoint layer below them.

## 4. Runtime model

### 4.1 Opening, running and closing a session

A library is opened with `openSession(SessionDeps)` followed by `start()`. `SessionDeps`
(`core.app`) carries everything a library needs: the filesystem, the two databases, the PDF, OCR
and XMP engines, the thumbnail store, a coroutine scope, the device settings as one
`SessionSettings`, a log, a clock, the installation id, and the source of every fresh name the
session draws (`IdSource`: journal actors, staging files, device ids; random in production, seeded
by the simulation so a seed replays). On a plain JVM they come from
`jvmSessionDeps` (`desktopMain`), the one place that wires java.nio, PDFBox, Tesseract and the two
Room databases under an `AppDirs`; Android's counterpart is `androidSessionDeps` (`androidMain`),
which dispatches on the location string. The desktop app, the `AppSessionRig` test harness and any
headless host build sessions the same way.

The open sequence is `SessionOpening.kt`: settle the device identity (`DeviceIdentity`), probe that
the storage can publish without replacement, fold the journal, then build every facet in dependency
order in `SessionGraph`, whose `prepare` does what opening does to the library's data (field seed
upgrades, adoption of this device's pre-journal settings and saved views). Those writes are best
effort and retried at the next open. An open refuses the library only for storage that cannot
publish without replacement (`OpenFailureReason.UNSUPPORTED_STORAGE`), a journal holding what only
a newer build reads (`NEEDS_NEWER_APP`), or a damaged journal over an index with no document to
show; a damaged journal otherwise opens the library read-only, writes blocked until a later pass
reads it whole (section 6.6).

`LibrarySessionImpl` itself holds only the lifecycle and the ingest side: imports, rescans, the
folder watch and the periodic reconcile. Every other facet is its own object from `SessionGraph`.

Everything the session runs is a child of one `SessionLifetime` job under `SessionDeps.scope`:
background loops, queue drains, and every facet write, which runs as work the session owns
(`SessionLifetime.owned`) rather than on the caller's coroutine. `close()` stops and awaits all of
it, then releases what the session holds open beside it (the coordinator, the endpoint client; each
registered with `SessionLifetime.releaseOnClose` where it is made, so a failed open releases it
too). Closing is final. The databases stay the host's, to close once `close()` returns.

### 4.2 What runs in the background

`LibrarySessionImpl.start()` launches, all logged through `runLogged` so a failure never reaches a
scope with no handler:

| Loop | Trigger | Does |
|---|---|---|
| Library watch | `LibraryFileSystem.watch()` events, settled into bursts | `watchAction` (pure) decides per event: ingest one journal object at once; reconcile the journal or the OCR sidecars once the folder goes quiet; rescan for PDF changes. |
| Initial rescan | Once at start | Opens the drains' gate when it succeeds. |
| Follow-up rescan | A scan deferred a file still being written, or an OCR read found stale bytes | One pending timer, rescheduled, that rescans after the settle window. |
| Rule mirror | The `AUTO_MATCH_RULES` setting changes | Caches another device's rule list into the index. |
| OCR queue watch | Rows other writers add to the OCR queue | Kicks the OCR drain. |
| Cloud sync schedule | Every minute, and soon after a journal change or a rescan | `SyncService`: a due-check against the configured interval, and one debounced pass after local changes, while automatic sync is on. |
| Maintenance tick | Every 15 minutes | Metadata reconcile, a retry of a failed initial rescan, and a kick of every queue so backed-off rows come due. |

Each queue (OCR, AI enrichment, queued AI edit runs) has a `QueueDrainer`: one worker loop fed by
conflated kicks, so a burst of kicks during a drain costs one more drain, and a step that throws is
logged and ends that drain, never the loop. Every rescan the session runs itself is followed by a
kick of every queue and the trash purge.

### 4.3 The single writer

`JournalCoordinator` is the only path that writes journal events and folds them into the index.
That is enforced by types: `ContentAddressedJournalWriter` is `internal` to `core` and the
coordinator's `writer` is private, so no caller outside `core` can reach a raw writer.

- Every fold, write and rescan runs on the coordinator's own dispatcher under one mutex
  (`locked`), so every entry point is main-safe and `close()` doubles as the barrier the session
  needs before its database is closed.
- The `FoldedState` is folded on demand, not at boot: readers go to the Room projection, and only
  writers and rescans pay for the replay, once per session.
- A step that reads and writes the journal under the lock does so through `LockedJournal`, which
  the coordinator hands to it (`serialized`).
- Every fold writes in one Room transaction (`Indexer.projection`), which a commit's journal
  bookkeeping joins, so no reader sees a row before its tags and fields. Moving a PDF to its
  intended path and recording where documents were seen are filesystem work, after the transaction.

**Decisions and the executor.** What the coordinating code decides is a pure function of what a
pass gathered, in plain files with no suspend, DAO, filesystem or mutex: `RescanDecision` (which
files a rescan hashes, and what each hashed file is to the rows: unchanged, refreshed, moved, a
duplicate, a new document, an edit in place, or superseded bytes that get no row; which shown rows
went missing), `FoldLifecycle` (a document's trash and delete flags as a fold projects them, a
rescan's resurrection of a document whose own bytes are back), `ImportDecision.row` (the row an
import writes) and `LifecyclePlan` in `core.app` (the events, row and file move of a trash, restore
or delete for good). The classes around them gather the facts (a listing, hashes, rows, the fold)
and carry the plans out, deciding the few-line cases inline (an import's duplicate or refusal, how
a reconcile extends or refolds the fold, which returns have settled); the coordinator is the one
executor that holds the mutex and mutates the session's state: the live fold, the synced settings
and the writer's state.

**The writer's state.** Two flags beside the live fold, written only under the mutex: what blocks
writes (a `MetadataWriteBlocked`, null while they are taken; `LibrarySession.writeBlock` mirrors
it) and whether a full refold is owed. A fold, reconcile or rescan pass runs in `latching`: when it
fails, writes are refused with the pass's own refusal, or `RECONCILE_FAILED`, and the live fold is
dropped with a refold owed, so the next reconcile or ingest refolds and reprojects in full; a
cancelled pass, or one that found a richer copy of an event only a full refold takes in, drops the
fold and owes the refold without blocking. Any pass that reads the journal whole and projects it
(`adopt`) lifts the block and settles the owed refold. The projection of a write that is durable
already (a commit's, an ingest's) runs in `withDurableProjectionRecovery`: a failure blocks writes
and rebuilds the projection from the store at once, which lifts the block when it succeeds; its
cancellation changes nothing.

Lock order: `BakeLock` (in-place bakes, trash and delete for good), then the coordinator's mutex.
Nothing that holds the coordinator's, the storage-path applicator's or the rule applier's lock takes
`BakeLock`. A lifecycle change, an import's return and a commit's projection run in `NonCancellable`
from the first effect that must not be left half done (a file moved, an object published).

### 4.4 The facet contract

Every `core.app` facet keeps one contract, so a caller needs no defensive wrapping:

- **Main-safe.** A viewmodel may call a facet from the main thread; the filesystem, Room and the
  PDF engines switch dispatchers for their own work.
- **Expected failures are typed outcomes** carrying an error enum (`ImportFailure`,
  `SplitFailure`, `BakeFailure`, `AiOcrOutcome`, `PermanentDeleteOutcome`), with the cause in the
  session log. `composeApp` maps each enum to a string resource; no English text built in `core`
  reaches the user. `DocContentFacade`'s nullable reads return null for anything they cannot show.
- **Bugs and misuse throw**, a closed session included. A viewmodel runs its writes through
  `launchReporting`, which turns a throw into a message instead of a crash.
- **One exception: the AI endpoint's failures throw.** `AiChat.ask`, `AiSuggest.suggestEdits`
  and `AiModelCatalog.listModels` pass on what the server said, which no enum could list, and the
  UI shows it as the reason. Do not wrap it in an outcome, and do not copy it for any other facet.
- **Cancellation is always rethrown** (`runCatchingCancellable`), never reported as a failure.
- **What refuses writes is observable.** `LibrarySession.writeBlock` is a `StateFlow` of the
  `WriteBlock` that refuses every write now, null while writes are taken: the writer's state
  (section 4.3) as each pass leaves it, from a read-only open on. It is the session's rather than a
  facet's because it is library-wide: every facet that writes is refused alike, each with a
  `MetadataWriteBlocked` carrying the same reason.

The facets stay narrow interfaces so each has a small fake. CONTEXT.md lists them.

## 5. Data flows

### 5.1 A metadata edit

```
viewmodel ──► facet (MetadataWriter, TagAdmin, DocLifecycle, ...)   runs as session-owned work
                 │
                 ▼
          JournalCoordinator.locked { fold on demand → build ops from live dots → write object → project }
                 │                                   │
                 │                                   └─► .npn/journal/objects-v2-xx/<sha256>.jsonl   (write-once)
                 ▼
          Indexer.projection { refreshEvents }  ──► index.db rows (documents, document_fields, document_tags, fts)
                 │
                 ▼
          Room DAO Flows ──► viewmodels (shared with WhileSubscribedTimeout) ──► Compose, a pure renderer
```

A storage-path rule follows metadata writes one at a time (`StoragePathRuleApplicator`), each
reading after its write committed, and journals a document's intended location once.

### 5.2 Import

`ImportService` reads PDFs from the picker, the scanner, a share intent or the watched folder,
hashes the bytes, checks for a duplicate (section 6.2), moves the file into the library, adds its
index row and queues it for OCR. Its only journal write is the restore of bytes the journal had
trashed or deleted, in the step under the writer lock that makes the row live. `OcrPipeline` then
writes the sidecar, after which the auto-match rules and, when enabled, AI enrichment write
metadata.

### 5.3 Changes arriving from the folder

Journal changes are routed apart from PDFs, so journal sync never scans or hashes PDFs:

- A new journal object is ingested on its own (`coordinator.ingestObject`).
- Once the folder goes quiet, the journal is reconciled (`reconcileMetadata`: reads only the
  metadata directory, skips unchanged source fingerprints, never walks PDFs) and the OCR sidecars
  are reconciled.
- A PDF change triggers a rescan (`coordinator.rescan`), which reconciles the journal first, then
  walks the folder: hashes new or drifted files once their bytes have settled (`WriteSettle`),
  judges trashed and deleted documents by the journal it folds as well as by the row, moves
  documents to their intended paths (`DocumentPathReconciler`), records restores (section 6.4),
  sweeps day-old temporary files (`TempFileSweep`) and reports (`ScanReport`).

Watch services and sync providers may omit events, so the 15-minute tick reconciles as well. On
desktop nothing else rescans until the folder changes; Android rescans on every resume.

### 5.4 OCR

The OCR queue in `device.db` records work owed per document. `OcrPipeline` drains it: picks the
engine with `OcrEngineSelector`, reads the document's bytes through `readDocument` (which checks
the hash), runs the engine, re-checks the hash before publishing a sidecar, indexes the text into
FTS, and, only for a sidecar this device wrote for the run it settles, fires `onTextProduced` and
then `onOcrProduced`. The auto-match rules hang on the first, automatic AI enrichment on the
second. Text adopted from a stored sidecar (a rescan's reconcile, another device's sidecar, a
rebuilt index re-queueing every document) is indexed and fires neither.

### 5.5 AI

`AiFacadeImpl` implements `AiChat` and `AiSuggest` and is the vision OCR engine, so all three share
one cached endpoint client (`AiRuntimeCache`). Consumers: `AiEditRun` (bulk, review then apply),
`AiEditQueue` (the same, handed to the background), `AiAutoEnricher` (unattended, queued after
OCR), and `OcrPipeline` through the vision engine, one call per page. `LibraryAiTools` is the
assistant's entire view of the library; `docs/AI_TOOL_DATA_GAPS.md` lists what it deliberately
leaves out.

### 5.6 Cloud sync

`core.sync` treats an rclone remote as one more replica of the library folder, never of the index.
One pass (`SyncEngine`) lists the remote (a failed listing fails the pass), checks the folder is
this library's, pulls the metadata the library lacks and folds it, identifies every remote PDF by
its SHA-256, plans (`SyncPlanner`, pure), applies the local half, pushes documents, and pushes
metadata last, so a replica that sees this device's journal event finds the remote files that go
with it already in place. The remote and schedule are the library's own on this device
(`CloudSyncConfig` in its `device.db`, through `SyncFacade.configure`), never device-wide.

A library can also start in the cloud: onboarding runs the same setup wizard on a setup-only engine
(`AppEnvironment.newCloudSetup`, sharing the device's rclone config), then `AppViewModel` makes the
library's local working copy in a folder of the app's own (`cloudLibrariesDir`: Android's no-backup
files, which need no storage grant; desktop's app data directory), named after the remote, opens it,
connects it with automatic sync on, and runs the first pass. The folder is still the source of
truth; the user just never picks it.

- Before anything transfers, a library with documents of its own refuses a folder holding no
  library but other files (`NOT_A_LIBRARY`: a drive's root, someone's Documents) or another library,
  whose journal shares no object with this one's (`OTHER_LIBRARY`): the two would merge for good.
  A library with no documents yet joins either (a new device). A folder a pass once found to be the
  library's is bound to it (`SyncManifest.bind`) and not checked again. The setup wizard refuses a
  remote's top level and a folder of other files already when it is picked.
- A journal that does not take writes ends the pass (`JOURNAL_BLOCKED`); one whose metadata pull or
  fold failed in part moves no document that pass (it still pushes its own metadata). No rescan runs
  between the fold and the mirror: it would see a file another device trashed still at its path.
- Journal objects, snapshots, OCR sidecars and device markers are a set union: immutable,
  hash- or size-verified, staged then published. Nothing deletes a journal object, so the union only
  grows. A remote copy of another size than the local one is an upload cut short, and goes up again.
- A remote PDF is identified by the manifest (`sync_remote_entries` in `DeviceDatabase`, keyed by
  remote path with the size and mtime it had when identified, and the library path it mirrored when
  last reconciled), else by the backend's own SHA-256, else by Dropbox's content hash matching a
  library file of the same size (`DropboxContentHash`: SHA-256 over the SHA-256s of 4 MiB blocks, as
  collision-resistant as SHA-256; the file's own SHA-256, read in the same pass, is then the remote
  copy's), else by a download, kept for installing only when the library has no such document.
  Weaker provider hashes (MD5, SHA-1, OneDrive's quickxor) never name a document. The manifest lives
  beside the device's own state because rebuilding the index must not mean downloading the whole
  remote again. Uploads stream through staging; no PDF is read into memory whole.
- Transfers run several at once (`transferEach`), as many as the remote's `TransferLimits` allow:
  Dropbox's were measured (metadata 16 down and 32 up, PDFs 8; past that Dropbox throttles), every
  other provider keeps metadata 8 and PDFs 4 until measured. rclone runs with `--transfers` 32,
  which sizes a Dropbox upload commit, and retries a copy Dropbox refused for lock contention
  (`too_many_write_operations`) twice. Only the transfer is
  concurrent: verifying, publishing, the manifest and the report take one file at a time. Remote
  moves and deletes stay in plan order; a run of consecutive uploads goes at once.
- A document's remote name follows the library's: where the indexer put the file (its journaled
  `storagePath`, or the name it gave a document whose intent another holds). Without an intent,
  the side that renamed it since the last pass wins (`ManifestEntry.localPath`), and a name made
  unique on the remote is the library's name too. Paths collide case-insensitively.
- Trash and delete for good are the journal's decisions, read from the fold (`JournalDocView`),
  not from index rows that may lag it. The pass mirrors them onto the remote, and a replica applies
  the same move to its own file, as a folder syncer would deliver it, only once the remote holds no
  live copy under any path; the rescan that follows projects it. That includes a document whose
  row still shows it because its file is still here: a rescan shows such a file until the restore
  settle window has passed, and the pass that carries the trash is what moves it. Each local trash, restore, rename
  and delete is decided again under the writer lock (`SyncHooks.serialized`), so a restore or an
  import the user made since the plan stands. A document the journal hides is never installed from
  the remote; one an import brought back unrecorded (no `doc-undelete` written yet) stays on that
  device only (`KEPT_ON_THIS_DEVICE`).
- Bytes a relink superseded (a bake's original) leave the remote before their successor goes up,
  and a replica holding them at the successor's path makes way for it.
- The remote's trash is purged by the local trash's rule (`TrashOps.purgeOlderThan`, section 6.3):
  an entry goes once every document its name could stand for left the library longer ago than the
  retention by the journal's trash times, and it was identified as one of them; never by mtime, and
  never one no document accounts for.
- A missing remote file is unknown, never a deletion; a remote PDF that could not be identified is
  left alone on both sides until it can be. Nothing is deleted or replaced to settle a conflict:
  occupied targets are reported (`ConflictKind`) and the copy named for its bytes (section 6.1).
  Every remote move and delete is guarded by a fresh `stat` against the size and mtime the plan
  was made from, every local move and delete by the file's hash, and a failed guard is a reported
  conflict, not an action.
- Known limit: renames are settled one at a time, each waiting while its name is taken, so two
  documents that swapped names (or a chain of renames) can stay apart: replicas then show the same
  documents under other file names. The library simulation compares cloud replicas without paths
  for now (`KnownIssues.CLOUD_NAMES_MAY_DIFFER`).
- The settings screen reads the pass as `SyncHealth` (`SyncService.health`, derived from the live
  status and the last pass's `SyncRunSummary`): the last success and its date, the
  consecutive-failure streak, the typed last failure, and the pending uploads/downloads the last
  plan found and did not settle. A planned transfer is settled once its file is across or found
  there already (another device's trash entry, a document already in place), so nothing done is
  left owed. A pass succeeds (`SyncRunSummary.synced`) only when it ran to its end and mirrored
  documents: one that could not pull every journal object of another device's mirrors nothing, and
  counts toward the streak like a failure. The headline says "in sync" only by `SyncHealth.healthy`
  (that pass, nothing to look at, nothing owed). There is no promise of the next pass's time:
  Android's background worker runs on its own schedule. The streak and last success, like the
  pending counts, are persisted in the `sync.lastRun` record in `device.db`; a pass that ended
  before planning carries the previous plan's counts, so a failure never claims there is nothing
  left. These counts are "as of the last check", never a live remote reading. A conflict is explained by its `ConflictKind`
  with the document's title where the library still shows it and the remote path the pass saw.

### 5.7 Library verification

`MaintenanceFacade.verifyLibrary` is the read-only check behind Library settings' "Verify library".
The journal says which documents should have a file (`JournalCoordinator.syncView` with
`everyNamed`: every document the fold names and shows), the index's rows only where each is: a document whose row the
index lost is still expected, at its storage path. `LibraryVerifier` checks each (a listed regular
file, the stored size, and a SHA-256 equal to the document id) and the journal object store (each
object's bytes, a recognized conflict copy's included, against the hash its name carries). It
reports missing/unreadable/size/hash problems, a document the library already lists as missing as
its own kind (`DOCUMENT_KNOWN_MISSING`: a loss, so the report is not clean, named by its library
path, never its internal sentinel), a misnamed file in one of this build's object buckets as
orphaned, and an applied object that is gone with an event no other object holds as missing (as
reconcile judges it; a newer build's store is no damage). The walk holds no lock and takes minutes,
so a document problem is reported only once it holds against a fresh read of the document and a
fresh listing. It never writes, repairs or deletes, never puts document bytes in its report, skips
symlinks with the library walk, and is cancellable with progress (between files, not within one).
A listing it cannot do is one `STORE_UNREADABLE` finding, not a problem per item. The settings
screen runs it as the session's own work (`startVerification`, `verification`), so it outlives the
screen, and its result waits until dismissed.

## 6. Rules by concept

The reference. Each rule names the class that keeps it. AGENTS.md's "Architecture invariants" is
the short list of these that a change must never break; this section is the complete one.

### 6.1 Document identity and paths

- A document is the SHA-256 of its bytes. Duplicates are forbidden: one canonical path per hash.
- PDFs are immutable after import; only rename and move change a file. The sole exception is
  explicit bake-in-place (section 6.5).
- Every path reaches disk through `NpnPaths.normalize`, which refuses traversal; observation-side
  predicates use `isSafe` to ignore rather than throw. `NpnPaths.Temp` names every temporary file
  the app writes into the library.
- A path holds one row (`DocumentDao.upsertAll`). A trashed, deleted or missing row gives its path
  up to any row written there, live or hidden, and waits on a sentinel path, the `.npn/missing/`
  prefix plus its document id (`DocumentDao`'s `missingSentinel`, a row path with no folder on
  disk), flags kept. A hidden row written onto a path a live row holds waits at its sentinel
  instead, so no rescan fails on where a hidden document's bytes turned up. `upsertAll` fails the
  write rather than drop a live row on a path another live row holds; before a rename or
  storage-path move onto such a path, `DocumentPathReconciler` does the same.
- Explicit renames and moves, and storage-path rules, write the `storagePath` system property
  (keyed by hash) before moving bytes. HLC last-writer-wins chooses the intended location across
  devices; `DocumentPathReconciler` materializes it on metadata refresh or rescan. The cached
  `relPath` is the actual location, never an unfulfilled intent. Trash and delete suspend path
  reconciliation. A PDF with no path intent keeps its discovered location. A moved row records the
  stat of the bytes the move checked (`DocumentPaths.settle`), never one of the target taken after,
  which by then may be a file a sync tool put there. A document whose intended path already holds
  its bytes takes that file and leaves the one it was at: the sync tool is usually still bringing
  that copy's deletion from the device that moved it, and a device whose intent is still the other
  path could delete the intended copy at the same time for the same reason, losing both.
- A name the app invents beside a taken one carries the first four hex digits of the SHA-256 of
  the bytes it will hold (`withNameToken`, `uniquifyByHash`): an import's `scan (7f3a).pdf`, a
  split part's `doc (part 1, 7f3a).pdf` (always, as two devices splitting one document make other
  bytes), a restore's `name (restored, 7f3a).pdf`, a storage-path template's second document, and
  cloud sync's copies. A counter is unique only in this device's folder, so two devices that have
  not synced would pick one `(2)` for different files, which the sync tool settles as a conflict
  copy no rescan indexes; by hash, different bytes get different names and the same bytes agree.
  A free name, the user's or a template's, stays as it is; when even the token is taken (two
  documents' hashes starting alike) a counter follows it.
- A file a user moved outside the app is journaled as the document's new `storagePath`
  (`ExternalDocumentMoves`), once this device saw it settled at its old path under the intent it
  still holds. A file at a path a `storagePath` event already named for the document, winning or
  overruled (`RescanDecision.externalMoveCandidates`), is never such a move: it is a sync tool's
  mirror of some device's move, and journaling it would set an older place against the newest
  intent, so two devices would move the document back and forth for good. The cost: a file a user
  moves back to a place the journal named before goes to its intended path again.

### 6.2 Import and duplicates

Kept by `ImportService`:

- An import is a duplicate only of a **live** document whose file holds the bytes. The size and
  mtime its row stored prove that, as they do for a rescan; a drifted stat means hashing the file,
  under the writer lock the check runs in. A caller may delete a duplicate's source.
- An import's row records the stat of the bytes it placed: the staged file's, which its move into
  place keeps, never a stat of the target taken once moved, which by then may be another process's
  file. A file written over the import in between has another stat, so the next rescan hashes it.
- The bytes of a trashed, deleted or missing document bring that document back, metadata and all,
  and any copy of it left in the trash is deleted, under the writer lock and only while the
  document is still live. The return is recorded in the journal in the same locked step that makes
  the row live (`LockedJournal.restoreReturned`); a failed one takes the import back in that step,
  so no trash or rescan lands in between.
- Bytes a relink superseded (a bake's original) are no document of their own: an import of them is
  a duplicate of the document they became while the library shows it, and refused otherwise
  (`SupersededImportException`). A rescan gives a file of them no row.
- A file is moved, trashed or deleted as a document's only while it holds the document's bytes by
  the same proof (`holdsDocument`) or a hash; another file at the document's path is left for the
  next rescan.
- Every delete of a file holding a document's bytes goes through `DeleteGate.reap`, by a
  `DeleteAuthority` it checks when it acts: the journal has the document deleted for good
  (`Tombstone`: a delete for good, an import taken back, cloud sync's copy of one), in the trash
  (`Trash`: the purge), or superseded by a relink (`Superseded`: a bake's original); or another
  file, verified, holds the same bytes (`ExtraCopy`: a duplicate's removal, a returned document's
  trash copies). The facts come from `LockedJournal` under the writer lock, or a fold the caller
  holds (`FoldedState.facts`). A sync tool carries a delete to every device, so a delete a device
  inferred from its own view alone is how a library loses its last copy; the simulation fails any
  delete of a folder's last copy of a document that its journal gives no fact for. Temporary files
  that hold no document are not deleted through it.

### 6.3 Trash, restore and delete for good

Kept by `DocLifecycleImpl` (its plans in `LifecyclePlan`), `TrashOps`, `JournalCoordinator.changeLifecycle` and
`TrashPurge`:

- `doc-trash`/`doc-restore` and `doc-delete`/`doc-undelete` are last-writer-wins pairs by HLC; an
  undelete also takes the document out of the trash.
- Trash, restore and delete for good read the row, check the file and move it under the writer lock,
  in one step with their journal write, so no rescan or storage-path move lands in between. A
  refused commit puts the file back, and so does a move that took the file and then failed: a
  trash's file left in the trash with no event is a document missing with its bytes in an entry
  newer than any restore, which nothing brings back.
- A trash moves the file at the document's path only while it is the document's own (stored size
  and mtime, else its hash). A file written over it since the last rescan stays, and the trash is
  recorded as for a document whose file is gone. One written over between that check and the move
  goes back once moved; when a process writing it in place has made a file at the path again by
  then, it goes beside it under a recovery name (`NpnPaths.Temp.recoveryPath`), never staying in
  the trash under the document's name, where no restore or purge takes it.
- A trash that moved a file records its size and mtime in the `doc-trash` event (`stat`, a payload
  key older builds ignore), by the stat that proved it the document's at its trash path; one that
  moved none records none. A sync tool carries mtimes, so another device's file at the trashed path
  with that stat is the copy the trash took, not yet removed there; a rescan decides a return by it
  (section 6.4).
- Restore and delete for good take a trash entry as the document's only when it hashes to the
  document: the hash prefix in its name (`NpnPaths.trashPath`) only narrows the search
  (`TrashOps.copiesOf`). A restore lists and hashes the entries before the lock and, under it,
  checks them by a stat, hashing again only one whose stat changed; it lists the trash again there
  only when no copy it found is left, or it found none.
- A delete for good acts on a document in the trash only; one restored meanwhile, here, on another
  device or by an import, is left alone. It deletes the bytes (copies in the trash, and a file at
  its path that holds them) after its tombstone is published, hashed before the lock and deleted
  under it only while unchanged and while the journal still has the document deleted. A file it
  cannot remove leaves the document deleted and is reported (`PermanentDeleteOutcome.FILE_LEFT`).
- Restoring or emptying the whole trash (`restoreDocs`, `deleteDocsPermanently`) lists it once.
- Undo of a trash (`undoTrash`, with the `TrashReceipt`s `trashDoc` returned) restores a document
  only while that trash still holds it: the receipt carries the trash time the row had once the
  trash stood, and a document trashed again since stays in the trash. The Trash screen's restore is
  unconditional.
- A document the journal has in the trash has a row, whether or not a rescan ever listed its file:
  rows otherwise come from the files a rescan lists, which never include the trash, so a rebuilt
  index or another device would show an empty Trash. The fold gives such a document a row at its
  sentinel, named after the path it was trashed from, with no stat (`FoldProjection.trashedRow`);
  a restore records the stat of the trash entry it moves, so the restored file vouches for it.
- Restore records its final destination: the path the document was trashed from (or the inbox under
  its file name when that is no document path), or a `(restored, 7f3a)` name beside it when a file is
  there now or another live row names it.
- A restore on a device that had another device's trash event but not yet its file move finds no
  copy in the trash and keeps the file still at the document's path; the move then lands, taking
  that file away and leaving the bytes in the trash. So does an Undo, or an import of the same bytes.
  A rescan that leaves a document the journal shows as missing brings such a copy back
  (`StrandedTrashCopies`), to its intended path or a `(restored, 7f3a)` name beside it, and scans again:
  only a copy that hashes to the document and whose name's trash episode
  (`NpnPaths.trashEpisodeMillis`) is no later than the restore that took it out of the trash last.
  A later episode is a trash the journal does not have: one whose event has not arrived yet, or one
  that never published it (its commit failed and its file could not be put back, or a crash cut it
  short). Such a copy comes back only once its episode is a day old (`RESTORE_SETTLE_MS`) and the
  session's rescans have found it so, with no trash in the journal, for a day as well, as a return
  is counted (section 6.4): a trash's event travels with its file move. An entry with no episode
  says nothing and stays. Left there, a copy would go with the first purge on a device that had not
  seen the restore yet, the library's last copy.
- The trash auto-purge runs after a watch-triggered rescan. It lists the trash before the writer
  lock and deletes an entry under it, in batches, once every document its name's hash prefix ties it
  to was trashed or deleted longer ago than the retention, by the journal and the rows as they
  stand there. The retention is the library's (`TrashRetentionFacade`, a synced setting); one this
  build cannot read purges nothing. An entry it cannot tie to such a document stays, however old.
- And only once every active device has acknowledged past the retention (`CausalPurge`): a device
  writes acknowledgment records (`AckRecords`, `.npn/acks/<deviceId>/<n>.json`, a write-once chain,
  each linking to the hash of the one before) that vouch for exactly the journal objects it
  published (`AuthoredObjects`, noted in `device.db` before each object is published, as no object
  says which device wrote it), and say how far it had applied every other device's chain. A trash
  at T counts once each active device has a record written at T + retention or later whose chain's
  objects are all applied here: any restore it made before that record is folded here, and one
  after is a restore past the retention. Active: it writes records, is not retired (the synced
  setting `device.retired.<deviceId>`), and wrote one within 90 days. A build that writes none is
  unknown, and goes by the retention alone. A device writes its first record at once, then at most
  daily when something changed and at least weekly. Cloud sync carries the records both ways, as it
  does the markers; its remote trash still goes by the retention alone. Settings > Library > Devices (`DevicesFacade`) lists them, with Retire.
- The keep store (`KeepStore`, `.npn/keep/<xx>/<sha256>-<episode>.pdf`) holds a copy of the bytes
  the app takes from a document's path, named by their hash and by the episode, the time of the
  event that kept them, taken before that event: published as a journal object is (a staged copy,
  verified, published without replacement, with the original's mtime), so an entry never holds
  other bytes than its name's. The purge takes an entry only for an event at least as late as its
  episode, so a later trash's entry, on a device whose event has not arrived here yet, is never
  taken for an earlier one's. An entry is a copy, never a state.
  It is written only once every device that may open the library reads it (`KeepGate`, the
  `keep-v1` capability in every active device's latest record; a device known by its installation
  marker alone, a build from before records, holds it shut until it is updated or retired, and the
  Devices screen says so). Then:
  - a trash publishes its event first, with the stat of the file it proved the document's, and only
    then copies that file into the store and deletes it (`LifecycleChange.afterPublish`). Cut short
    in between, the file stays at its path with the recorded stat: in the trash, its file back;
  - a restore with no trash entry here copies the document back out of the store once its events
    stand; a rescan brings back a restored document whose file is gone (`StrandedTrashCopies`, its
    first source), as the journal shows it and the store holds it; never one not restored, so a
    file the user deleted outside the app stays deleted;
  - a delete for good takes the store's copy with the rest; a bake's original and an import taken
    back (a split's rollback) are kept there too before they are deleted.

  Every build reads it whatever the gate says. The purge takes an entry by the trash's rule
  (`FoldedState.keepTime`): a trashed or deleted document's once that is past the retention and
  acknowledged, a superseded one's once its relink is, one the library shows once its last restore
  is and while its file holds its bytes at its path. Cloud sync does not carry it (each device keeps
  its own), and `.npn/trash/` entries stay a source for restores and are purged as before.
  An entry's name carries its episode (`<sha256>-<episode>.pdf`, the time taken before the event
  that kept it), and the purge takes one only for an event at least as late: one name shared by
  every episode would let a device's purge take the copy another device has just kept for a trash
  whose event has not arrived. A trash's file leaves its path by a claim (`DeleteGate.reapClaimed`:
  an exclusive rename to a recovery name beside it, then the claimed file checked by its hash), so
  a file a sync tool puts there after the check is never the one deleted.
- Durable intents (`PendingIntents`, `device.db`'s `pending_ops`): a trash into the keep store and
  a bake note themselves before their first file change and close the note after their last. Before
  every rescan, what a crash or a failure left open is settled by what the journal says: a trash
  whose event stands has its file finished into the keep store, one whose event never stood leaves
  it; a bake whose relink stands has its original kept and deleted, one without gets its original
  back at its path when that is free. A note of an operation still under way is left alone.

### 6.4 Rescan, settling and resurrection

Kept by `Indexer` (its decisions in `RescanDecision` and `FoldLifecycle`), `WriteSettle`,
`JournalCoordinator.recordRestores` and `OcrPipeline`:

- A rescan hashes a PDF only once its bytes have settled (`WriteSettle`). Files written in place
  are deferred and picked up by a follow-up rescan, whichever rescan deferred them; a relink or a
  bake hands the report of the rescan it ran to the session, which follows it up as its own.
- A stat (size and mtime) proves a file's bytes through `FileMeta.hasStoredStat`, the one place every
  proof by stat goes through: the rescan's fast path, an import's duplicate check, `holdsDocument`,
  the trash and duplicate removal.
- `OcrPipeline` re-checks the hash of the bytes it read before publishing a sidecar, so a partial
  file never gets a document identity. A read that finds other bytes asks for a follow-up rescan
  and leaves its run queued, uncharged; a second one in a row counts as a failure.
- A rescan skips a file it cannot read rather than failing, and treats a failed read as not held.
- When the bytes of a trashed or deleted document come back, the return must be recorded in the
  journal, or the next fold hides the document again. An import records it at once (section 6.2).
  A trash that moved the document's file records that file's size and mtime (`doc-trash`'s `stat`,
  which the fold keeps as `FoldedState.trashStat`), and a rescan goes by it (`FoldLifecycle.resolve`):
  - the bytes back with that stat (mtimes within 2 s) are, as far as any device can tell, the copy a
    sync tool has yet to take away, since sync tools carry mtimes; or a put-back that kept the mtime
    (a file manager's copy, a backup). The document stays in the trash however long they stay,
    reported in `ScanReport.filesBack` (the Trash screen says its file is back), and a restore takes
    that file where it is (`DocLifecycleImpl.restore`);
  - the bytes back with another mtime are no such copy: a deliberate return, written at once.

  Without a recorded stat (a trash that moved nothing, one an older build wrote, a delete for good),
  a rescan that finds the file back writes it once the event that hid the document is a day old
  (`RESTORE_SETTLE_MS`) and the session's rescans have found the file back, scan after scan, for a
  day as well, as the scan's last step; sooner, the file may be one a sync tool has not taken away
  yet, and the rescan shows it for that scan only. The second day counts from this device's own
  sighting because a device that trashes and syncs a day later delivers its event and its file move
  after the first day has passed, in either order. A new session starts that count again. A failed
  write is logged and left to the next rescan.
- The file wins only with the document's own bytes: the rescan must know the hash of the file at
  the document's path, or at the path it was trashed from when no other row holds that, and the
  hash must be the document's. Another file under the same name, or a file still being written,
  brings nothing back.
- `doc-undelete` is newer than the other lifecycle ops. This build reads and folds it, and writes
  it only once every device that may open the library reads it: the same gate as the keep store's
  (`KeepGate`; the coordinator's `undeleteRead`), as every build that lists `keep-v1` reads the op.
  `JournalOp.DocUndelete.WRITTEN` stays false, so a build from before the op never meets one while
  such a device is active. Until the gate opens, a deleted
  document's return records nothing: an import of its bytes stands until a fold hides it again, a
  rescan shows its file for that scan only, and a delete for good that still removes the bytes
  leaves such an import's file in place, since the coordinator remembers, for the session, which
  deleted documents an import brought back unrecorded. A trashed document's return still writes
  `doc-restore`.

### 6.5 Relink and bake-in-place

Kept by `BakeService`, `BakeRelink`, `DocContentFacadeImpl` and `FoldedState`:

- Bake-in-place replaces only the bytes it read and hashed as the document's, while the file keeps
  the size and mtime it had then, and records a `relink` journal event for the new hash. It moves
  the original aside under a recovery name rather than deleting it, and puts back a file that turns
  out not to be the document's (another process or a sync tool replaced it after the bake's last
  look), so it never deletes bytes it did not prove; the original goes once the relink stands. One
  a crash or a failed delete leaves under its recovery name, the next rescan deletes
  (`Indexer.removeSupersededOriginals`): a file under a recovery name whose bytes a relink
  superseded, while the document they became is in the library with its bytes at its path, and
  only while the file still holds those bytes. Never another file of them, and never the
  successor's, so no last copy of a document goes. Between the original's move aside and the
  baked bytes' move in, the path is free for a moment; whoever takes it then, the bake's undo
  leaves that file alone and the original under its recovery name (an accepted race, logged as a
  warning). Bakes run one at a time, each whole, under the session's `BakeLock`; every bake of a
  path writes the same temporary file, and its relink must name the bytes it installed. A trash or delete for
  good holds that lock too, so it waits for a bake and then acts on the document the bake made
  (`changeLifecycle` follows the relink).
- **A relink target inherits its chain.** A relink (a bake, or a missing document relinked to the
  file that took its place) writes one `relink` event and nothing else. The fold shows the target
  its predecessors' metadata as its own (`FoldedState.inheritedFields`, `inheritedProp`,
  `inheritedTags`): per field or system property one register over the whole chain, the latest
  write winning, a clear included, and the chain's live tag adds as one OR-set, so a removal from
  the target lists the predecessors' dots too (`tagRemovalOps`). Every reader of a document's
  metadata, the projection, the metadata writer, the rules and the path rule included, reads the
  inherited views; the own-register views are test seams. The relink op supersedes: the old
  document is hidden (`resolveDocId`, `isCurrent`) and its bytes are a duplicate of the target
  while the library shows it (section 6.2).
- So an edit of the old document made after the relink, by a device that had not seen it, reaches
  the target as soon as it is folded, and nothing is copied, converges or can overwrite a newer
  edit. A document's history is its own events.
- A bake changes no page, so its relink hands the original's text on: before it, the original's OCR
  versions are published again under the baked hash as this device's (`OcrPipeline.copyText`) and
  its enrichment mark is copied; after it, that text is indexed, so the baked document is
  searchable at once and is neither OCR'd nor enriched again. A relink to other bytes hands on
  nothing but the inherited metadata.

### 6.6 Journal storage

**Formats.** Every transaction is one storage-v2 object,
`.npn/journal/objects-v2-<xx>/<sha256>.jsonl`, named by the SHA-256 of its bytes, with a fresh
actor id per transaction. A per-device v1 segment, `.npn/journal/<deviceId>/<seq>.jsonl`, a format
no released build ever wrote, is no longer read and is passed over like any unknown file. There is
no fallback format. Beside its objects a bucket may hold their staging files and a sync tool's file for
one still in transfer (`NpnPaths.isSyncTemp`: Syncthing, Nextcloud and ownCloud, Resilio), which a
reconcile passes over and never deletes; any other name there fails the pass. A staging file a
crash left behind, on any installation, is the rescan's `TempFileSweep`'s to delete once a day old.

**Publication.** An object is staged under a `.part` name in its bucket, forced to the device
(a hash check reads the page cache, and a name can reach the disk before the bytes it names), then
hash-checked and published without replacement, its directory forced after: a hard link, or where the filesystem has none (Android shared
storage, exFAT) an exclusive rename (`renameat2(RENAME_NOREPLACE)` on Linux and Android,
`MoveFileExW` without `MOVEFILE_REPLACE_EXISTING` on Windows), in a bucket whose directories carry
exactly their names. A filesystem with neither primitive throws
`VerifiedPublicationUnavailableException`, and the library refuses to open rather than write
anything. `stageAndPublishVerifiedObject`, beside `ContentAddressedJournalWriter`,
is the one helper for it.

**Nothing is deleted; snapshots are read, never written.** The app never deletes a journal object
and never writes a snapshot. Earlier builds compacted the journal: they folded old objects into a
snapshot (`objects-snap-v2-<xx>/`) whose manifest names the hashes it supersedes, and deleted those
objects. Snapshots are still read and folded as sources; an object one supersedes that is still on
disk (a delete that failed, a copy put back) is read like any other.

**A lost source.** A source the index applied that is gone from the folder is a loss unless every
event it held, by device id and sequence, is held by an applied source the reconcile listed
(`SourceSetValidator.unheldEvents`, from the event tables): the snapshot an earlier build compacted
it into, directly or through a later one, or, for a gone snapshot, the objects it replaced, back on
disk (a folder put back from before a compaction). Such a source is forgotten, as a rebuild would;
the events, and the fold, stay as they were. A manifest vouches for nothing it does not hold. A loss
blocks writes (`WriteBlock.JOURNAL_DAMAGED`) at the first pass that sees it, and a pass that reads
a journal holding those events again lifts the block. An object lost for good, which no device
holds any more, would block this device forever while a device that never saw it writes on: the
user's exit is `MaintenanceFacade.rebuildIndex` (`JournalCoordinator.rebuildIndex`), which forgets
every applied source and event and refolds the folder's journal as a fresh index's open does, so
what only the lost object held is forgotten and writes are taken again. There is no grace window, since objects are
write-once and named by their hash, a sync tool never swaps one in place, and a file still in
transfer shows under its temp name rather than as a gap (`SourceSetValidator`'s KDoc has the
reasoning). An open that finds the journal not folding whole opens the library read-only rather than
refusing it, with writes blocked until a pass lifts them, unless the index has no document to show
(`JournalCoordinator.refreshState`); one that finds an op only a newer build reads refuses it.

**Compatibility.** A build folds only the ops it knows. An object, snapshot or segment holding an
op it does not know, or a known op it cannot read (a `prop-set` of a newer system property, a
`field-def` of a newer field type, a newer rule algorithm or view type), is refused as unsupported:
that build blocks writes and stops opening the library until it updates, and says so
(`MetadataWriteBlocked` carries `WriteBlock.NEWER_FORMAT`, told to the user as an update to
install). There is no skip path, so a new op ships in a build that reads it before any build writes
it; `WireFormatTest` pins the op names. A payload key a build does not know breaks less: its fold
ignores the key, and the object keeps it. Earlier builds' compaction encoded the events it carried
again from their own index, without such keys, so a snapshot from a build before `perDocument`
holds field definitions without the flag. Such a copy is still the same event: two copies of one
event identity where one lacks only keys of the other are one event, and the index keeps the
richer, whichever it read first (`EventCopies`; a fold that had the poorer one folds again in
full). Any other difference is a collision that blocks writes. An index rebuilt once the original
object is gone has only the poorer copy.

**Device identity.** `device.id` in `device.db` names what this device writes under its own name:
OCR sidecars and the watcher's filter of its own writes. Two installations must never share one, so
`DeviceIdentity` ties it to the installation (`SessionDeps.installationId`, kept on Android in the
no-backup directory) twice: `device.db` records which installation the id belongs to, and the
library holds one write-once marker per installation that used it,
`.npn/devices/<deviceId>/<installationId>.json`. Either disagreeing gives a fresh id at open; a
fresh id costs nothing, as the old id's sidecars are adopted as another device's. Publishing that
marker uses the journal's primitive, and once it is there every open publishes and deletes a
throwaway object under `.npn/tmp` the same way, which is how an unsupported filesystem is found out
at every open.

### 6.7 Fold and projection

Kept by `FoldedState`, `MetadataAccumulator`, `FoldProjection`, `Indexer`:

- The fold is OR-set tags and HLC last-writer-wins registers; `FoldConvergencePropertyTest`
  property-tests its convergence.
- The projection is a pure function of the applied source set. `MetadataAccumulator` fingerprints
  that set beside the ready marker: an open whose fingerprint still matches reuses the stored
  projection instead of refolding. The fingerprint is an order-independent digest kept up to date
  per changed source (`AppliedSourceSet`), so a commit does not re-read every source row. **Changing what a fold produces from the same events requires
  bumping `MetadataAccumulator.PROJECTION_VERSION`**, or existing installs keep rows built by the
  old fold.
- Field values land in `document_fields` (one row per document and key, canonical string plus a
  numeric shadow for NUMBER and DECIMAL) and definitions in `field_defs`; the five typed columns on
  `documents` are a read cache written from the same map, never a second source. One FTS `fields`
  column indexes every value; a document's FTS row is reached through `fts_keys`; the fold writes
  its metadata columns and `OcrPipeline` its text, and neither rewrites the other's.
- `LIVE_DOCUMENT` in `core.db` is the one SQL predicate for what the library shows.
- A pass that failed or was cut short drops the fold and leaves the writer blocked or owing a
  refold (`JournalCoordinator`, section 4.3), so the next reconcile or ingest refolds and
  reprojects in full (`recoverDurableProjection`) rather than build on what it had. A reconcile
  extends the live fold only with events it purely added; a wiped projection, a mutated source or a
  richer copy of a stored event folds again in full (`MetadataAccumulator.reconcile`).
- A journal source is re-read only when its size or mtime moved; one applied before is then
  recorded again with its new fingerprint, which a reconcile pays for with a full refold even when
  only the mtime changed. A reconcile pass that holds a fold marks the projection reconciling until
  the coordinator marks it ready again, so a pass cut short leaves the next open refolding.

### 6.8 Fields are data, not code

- Title, correspondent, docType, docDate and notes are ordinary `field-set` fields whose definitions
  the library seeds (`FieldKeys`); there is no per-field Kotlin. A change to the seeds raises
  `FieldKeys.SEED_VERSION`, and `FieldKeys.upgradeSeeded` brings older libraries up once, never
  overriding what their users changed. A library's first seeding is written beneath every edit
  (`JournalCoordinator.commitDefaultsBuilt`: clock at physical time 0), as a device that opens the
  library before another device's journal reached it seeds too, and its seeds must lose to what
  that device's user changed in the fields once its journal arrives.
- An exhaustive `when (FieldType)` belongs only in `FieldValues` (canonical form), `FieldEditor.kt`
  (the editor and its type label) and the filter sheet's per-type control. Everything else reads
  the `FieldDef`'s flags, hint and the capabilities each `FieldType` constant declares (`aiLiteral`,
  `isTextual`, `isNumeric`, `isDate`, `singleLine`, `hasVocabulary`). `./gradlew
  checkFieldTypeBranches` fails any other branch on a type.
- Storage is always the canonical form; a blank value clears the field and is never stored as `""`.
  Each number has one canonical form, so canonical strings compare as values; the fold reads what
  older builds stored through `FieldValues.normalized`.

### 6.9 Library settings, storage-path rule and auto-match rules

- Library data every device must agree on is journal data: synced `setting-set` keys listed in
  `LibrarySettingKeys` (`LibrarySettings.kt`), read through `JournalCoordinator.settingFlow` and
  written with `putSetting`. This device's own settings (`core.settings`) are never per library and
  never synced. A device's pre-journal settings are adopted once by a library that has no value
  yet, as a group adopted whole or not at all (`adoptSettings`).
- The rules table in the index only caches the journal's list (`AutoMatchRuleSource` mirrors it).
  A rule is normalized where it is saved or decoded (`AutoMatchRule.normalized`). Content rules
  match on the indexed text when a write re-runs them.
- Auto-match rules only fill in what a document has never had, and run on text new to the library
  or when a person acts, never when an index re-indexes text: a rule sets a field the journal holds
  no value for on the document (a cleared one counts as held) and adds a tag it never carried (a
  removed one counts), when this device's OCR reads the document's text, after an edit to its fields
  or a rename or move, and over every live document when a rule is saved or enabled
  (`RuleApplier`, deciding against the fold its commit lands on). So a rebuilt index, a device
  that joins, or a sidecar another device wrote writes nothing, a person's value or removal is never
  undone, and the rules need no op of their own: the device that read the text applies them once,
  and the others get the result through the journal. A rule that arrives after a document's text was
  read reaches it only when a rule is saved or enabled, and a rule edited later does not rewrite
  what its earlier version filled in.
- A tag the user creates on the Tags screen, which no document carries yet, is kept by a per-tag
  setting (`LibrarySettingKeys.KEPT_TAG_PREFIX`, `TagRetention`), not by a style: the fold's
  `retainedTags` keeps its row while the projection prunes every other tag nothing carries. A
  delete clears the marker; a rename moves it, a merge into a tag documents carry drops it, and the
  fold carries a marker along a 0.1.0 rename that left it behind.
- A storage-path template that cannot expand says why with a `StoragePathError`; its context always
  carries the registry's field types, which decide which fields take a date pattern.
- A watched inbox folder at or inside the library (`liesInLibrary`) is never swept, and Settings
  refuses one when picked. Delete-after-import removes a source only while it still holds the bytes
  imported: while it has the size and mtime it was listed with, or, from a provider that listed no
  mtime, while it hashes to the document. And only once the library is proved to hold the
  document, asked last inside `WatchedFolder.delete`, after the source's own check and right before
  it goes: a library file that went before then leaves the source as the document's last copy.

### 6.10 OCR sidecars, versions and engine selection

- Sidecars are keyed by document hash and writer (device id plus engine) and are write-once
  (`OcrVersions`, `OcrSidecarName`, legacy forms included). A sidecar that does not read is passed
  over for the next best, and this device's run is judged by the ones that do.
- `OcrEngineSelector`, not `OcrPipeline`, decides when the paid engine runs for a document: the
  per-document rule (engine pin, page threshold) over `UnattendedAiPolicy`'s yes or no. Its page
  threshold binds the unattended path only; an asked-for run (`OcrFacade.ocrWithAi`) runs at any
  length, and `aiOcrOffer` warns the user first. "Asked for" means asked on this device: `ocrWithAi`
  writes the engine pin, which syncs and only selects which version is searched, plus
  `ocr_queue.aiRequested` in `device.db`, the local request to pay. An `ai` pin synced from another
  replica selects that replica's sidecar, or runs the model here only as unattended work.
- A re-select of OCR happens once per settled burst of journal objects, not once per object.

### 6.11 Queues and drains

- A queue row records only that work is **owed**, never that it is under way. No queue table has a
  `running` state: the drain is single-flight in-process, and a row left claiming to run would be
  skipped forever. "Who is being worked on right now" is a `StateFlow` on the live queue
  (`OcrPipeline.inFlight`, `AiEditQueue.inFlight`).
- An interrupted pass leaves its row exactly as it found it, attempt count included, which is why
  cancellation is rethrown rather than recorded as a failure. A pass that ends leaves a request made
  while it ran on its row, owed (`OcrQueueDao.settle`).
- No drain starts before the session's first successful rescan and its OCR reconcile: a drain drops
  this device's work for a document without a row, and until then the index may not know the
  library's documents. `QueueDrainer` holds its drains at that gate.
- The enrichment and edit queues have no table observer, which could not tell new work from a
  failed row being rescheduled; each kicks its own drainer when it records work (`drainedQueue`'s
  `onEnqueued`). The OCR queue, owned by `OcrFacadeImpl`, kicks on its own enqueues and watches the
  table for rows other writers add. Time-based retries come from the maintenance tick
  (`QueueRetryPolicy`'s `backoffMs` ramp, stored as `nextAttemptAt`).
- `EndpointHealth` is the one cooldown of an AI endpoint, held by `SessionGraph` and shared by the
  enrichment and edit queues: a failed call in either holds both back, an answer to either lifts
  it; a write that fails after an answer is its row's own failure.

### 6.12 AI spending and endpoints

- `UnattendedAiPolicy` is the one class that answers whether this replica may spend unattended, for
  both paid unattended paths (automatic vision OCR, automatic enrichment), from the user's
  `AiConfig` toggles. Consumers never read `AiConfig.ocrAuto` or `autoOnOcr` directly.
- `AiFacadeImpl.suggestEdits` holds the one ceiling on edit suggestions in flight
  (`MAX_CONCURRENT_SUGGESTIONS`); concurrent runs, the queued drain, automatic enrichment and single
  requests together never exceed it. `SuggestionRunner` bounds one run's fan-out.
- Every endpoint client is built in `AiRuntime.build(config, transport)`: the facade's cached one,
  and the short-lived ones `AiSelfTest` (which makes paid calls) and `AiModelCatalog` build per use.
  `transport` is the test seam. `ProviderClientFactory` is the one table of per-provider behaviour.
- The AI edit queue is the pattern for deferrable work a human must confirm: persist the request
  and its subjects, buy the expensive part on a drain, store the answer verbatim, surface it on the
  processing queue. `AiAutoEnricher` is the pattern for work that writes unattended.

### 6.13 App lock

Kept by `core.security` (`AppLock`, `LockCrypto`), `SettingsCodec` and `composeApp`'s
`ui/lock`:

- The lock is access control over the app's UI, **not encryption**: the library folder stays
  plain files, readable by anything that can open them, and the Settings copy says so. It is a
  device setting (`SettingsSnapshot.appLock`), never journal data: another device opening the
  same library has its own lock.
- Only a salted PBKDF2-HMAC-SHA256 hash (`LockCredential`) is stored, with its iteration count
  and a random per-credential salt; the secret itself is never persisted, logged or put in saved
  state (`LockScreen` keeps it in an unsaved `remember`, and `LockViewModel` only receives it for
  one check). `LockCrypto.verify` compares in constant time.
- `AppLock` starts locked whenever the setting is on, which is what a cold start shows. Returning
  from the background locks once the elapsed time reaches the chosen `LockTimeout`
  (`IMMEDIATELY` = any return). On Android, `LockLifecycle` (`composeApp` `platform`) turns the
  activity's lifecycle into the lock's: every real `onStop` records when the app was left, and
  `onStart` checks the time away. A stop for an activity the app started and expects back (a
  picker, the share sheet, printing; `LockActivityHandoff`) is not exempt: it gets a bounded grace,
  `max(timeout, 3 min)`, so leaving for Home from there still locks once that has passed. Back,
  a stop with the screen off, and the screen going off later (an `ACTION_SCREEN_OFF` receiver)
  end the grace. A configuration change is not background. While the lock is on, the window
  keeps `FLAG_SECURE`, locked or not, so the Recents thumbnail never shows the library. Desktop,
  whose minimize events many window managers never send, locks after the chosen interval without
  keyboard or pointer input, and treats a minimized window as background too; it offers no
  `IMMEDIATELY` (`LockTimeout.choices`) and defaults to five minutes.
- Locking removes the app's content rather than covering it (`LockGate`), so nothing behind it is
  read out, focused or reachable by a shortcut; a `SaveableStateHolder` keeps what the content
  saved, so an unlock returns to the same document, dialog and scroll position. Imports shared
  into a locked app run, but their result toast waits for the unlock, as a duplicate count says
  what the library holds. Ctrl+Shift+L and Settings → Security lock the app at once.
- Wrong secrets back off (`LockBackoff`: three free attempts, then 5 s, 15 s, 1 min, 5 min). The
  count and the retry time (`LockAttempts`) are stored beside the setting, so a restart does not
  reset them; a stored or running wait is held to what its count earns, so a clock set back, or
  Android's clock starting over at a reboot, cannot stretch it. The whole state is one
  `StateFlow`, so the shell can read it while the hash runs off the main thread. Turning the lock
  off, changing its PIN or its timeout asks for the current PIN first (or the device's own unlock
  on Android), through the same `AppLock` check and backoff as the lock screen. Enabling the lock
  or setting a new secret does not lock the app that made the change; disabling it clears the hash.
- The user cannot be locked out for good. Android's `AppLockAuthenticator` offers
  `androidx.biometric` with `DEVICE_CREDENTIAL`, so the device's own unlock always works, and the
  app PIN remains an alternative. On desktop, closing the app and deleting the `appLock*` entries
  from `settings.properties` (or the file) while it is closed removes the lock; the Security pane
  and the lock screen name the path.
- No journal op and no `device.db` change: an older build simply has no lock and opens the same
  library.

## 7. UI architecture (`composeApp`)

### 7.1 Shell and state ownership

`App.kt` and `AppViewModel` open, restore and switch the library behind one sealed `AppState`
(`Restoring`, `Opening`, `NoLibrary`, `RestoreFailed`, `Open`), which also carries every failure to
open as a localized `OpenFailureReason` with the technical cause as an optional detail.

Above every screen, `MainNav` stacks the shell's `WithTopBanner` banners: the update notice, the
read-only banner (`WithWriteBlockBanner`) and the unsaved-drafts one. The read-only banner shows
`AppViewModel.writeBlock`, the open session's `LibrarySession.writeBlock`, in the same sentence a
refused write gives as its reason (`WriteBlock.reasonText()`); it cannot be dismissed and goes once
a pass reads the journal whole, and its Retry (not offered for `NEWER_FORMAT`) is a rescan.

A change of the app's language rebuilds everything under `LocaleProvider`, so `LocalizedApp`
remembers what the open library's UI must keep through one holder above it, `LibraryUi`: the
navigation, and what the session keeps for its screens. All of it belongs to one session.
`LibraryViewModelStores` (held in the window's store, the activity's on Android) keeps one
`LibrarySessionUi` per session, the one place that state lives: the viewmodel store its screens use
(`LibraryViewModelHost`), each document's store (shared by the screens showing the document, held
through a `ViewModelStoreLease`), the unsaved drafts (saved only to seed a restored process), and
what the screens hand each other. It clears a session's state once its UI is gone, so nothing a
closed library's screens started runs on.

**What survives recreation.** A screen is rebuilt far more often than its library changes: a
recreated window or activity, a restored process on Android, a change of the app's language (which
rebuilds everything under `LocaleProvider`) and a change of width class (which swaps the layout, and
with it the composables). A composable's own state, `rememberSaveable` included, survives only the
first two, and only where it keeps its place in the tree. So whatever the user would miss (a
selection, text typed into a screen, the pane, tab or document it shows, an open dialog or
confirmation and what it is about, a review under way) is screen state, and lives in the screen's
viewmodel, never in a composable. Screens' viewmodels live in their back-stack entries, whose stores
`LibrarySessionUi` holds above `LocaleProvider`, so they outlive all of the above but a process
death. For that one, a viewmodel takes the `SavedStateHandle` its entry hands it
(`createSavedStateHandle()` in the `viewModel {}` initializer) and keeps each such value in a
`SavedUiState` (`ui/common`; or saves the part of its own state worth keeping with `saveUiState`):
saved as `NpnJson.Persisted` JSON on every change, restored when the new viewmodel is built, and
dropped when too large for Android's saved state (a selection or a filter over thousands of
documents comes back empty). A document's viewmodel lives in the document's own store, which no
entry saves, so it keeps the page without a handle: the view showing it saves the page in
`rememberSaveable`, and a viewmodel that view builds starts there. The document's panel tab and the
fields added to the panel before they have a value are the composables' own `rememberSaveable`
state. The tab is `MetadataPanel`'s, above the moved tabs: the wide layout composes them in a
subcomposition (`BoxWithConstraints`), and movable content keeps its state moving out of one, not
into one, so what the tabs hold survives a window turning compact, and only the tab survives it
turning wide (the side panel's tab does not carry into the detail screen it expands into). State the screens share
or hand each other belongs to `LibrarySessionUi`: the drafts (saved at the root, for a restored
process) and the hand-offs (not saved; a viewmodel that needs one after a restore, as AI edit does
its documents, saves its own copy). Dialogs follow one rule: the viewmodel holds *which* dialog is
open and what it is about (the field being edited, the document being deleted); the dialog holds
its own buffer (the text typed, a box ticked, a pending confirmation) in `rememberSaveable`; and a
host whose place in the tree depends on the width class moves as one `movableContentOf` (the
navigation content, a document's metadata tabs, the open settings pane), so the buffer keeps its
place across the breakpoint. A language change cannot happen under a modal. A plain yes/no
confirmation with nothing typed into it is the composable's own `rememberSaveable` too: nobody
misses it, and a viewmodel key for it buys nothing. What a composable may keep without saving is
what nobody misses either: hover, focus, an open menu, animation, a gesture under way, a splitter or
scroll position. What a restored process legitimately loses is work in flight (a rescan, a model
call, a write), messages not yet shown, what lived only in memory (results fetched on demand, the
last scan's report), and what is one tap from its default (what a queued review had ticked, which
reopens with nothing ticked). A viewmodel that reads saved state has a test that recreates it
mid-interaction through `Recreatable` (`composeApp` commonTest), which saves the handle as a process
death does, builds a new viewmodel from it and the same fakes, and checks the screen renders the
same; its `Transient` lists what a restored process may lose there, each with why.

### 7.2 Dependency wiring (`app/di`)

`AppEnvironment` owns the session lifecycle: open, close, swap, the watched folder. A swap is done
once the new location is saved; a failed one, saving included, closes what it built, reopens the
previous library and rethrows. The platform source sets hand it two plain functions in
`AppEnvironment.android/desktop.kt`: one that builds a location's `SessionDeps`, one that opens its
watched folder. The per-library wiring itself is `core`'s. The DI package imports no UI package.
`app/platform/` holds the small `expect` hooks each platform answers (folder access, library-folder
creation, image decoding, sharing, which settings it offers, the scanner, foreground import,
Android's `LaunchImports`).

### 7.3 Screens

One package per screen under `app/ui/`: `library/`, `detail/`, `tags/`, `rules/`, `settings/`,
`trash/`, `queue/`, `onboarding/`, `ai/`, `update/`, `whatsnew/`, `nav/`, `common/`, `theme/`.
Viewmodels sit beside their screen. `whatsnew/` shows the bundled `composeResources/files/whats-new.md`
once per version; `settings/` owns the About pane's diagnostics report through `SettingsViewModel`.

- Leaf composables take state plus an actions interface, never a viewmodel: a screen hands its
  viewmodel down as a small interface it implements (`LibraryActions`, `DocumentWrites`,
  `DocumentActions`, `MetadataActions`, `SettingsActions`, `TagWrites`, `TrashWrites`,
  `ReviewController`, `AiEditSetupActions`).
- `DocumentView` is the one wiring of a document to its `DocDetailViewModel`; the detail screen and
  the library's side panel frame it with their own chrome and share it.
- Wide-or-compact comes from `LocalWindowWidthClass`, never a screen's own check.
- Shared pieces live in `ui/common`, one per UI concept: the form blocks (`SettingsSection`,
  `SwitchRow`, `HelpText`, `Dropdown`, `ExpanderRow`), `SuggestField` (the one autocomplete),
  `TypedTextField`, `rememberNumberInput`, `BackTopBar`, `TooltipIconButton`, `TopBarActions`,
  `ActionMenuItems`, `keyShortcuts` (the one way a screen binds keys), `FocusOnEntry`, `FocusRing`,
  `ReducedMotion` (the one reduced-motion answer), `EmptyState`, `TagEditor`,
  `TagFormDialog`, `TagPickDialog`, `DocThumbnail`, `ConfirmDialog`, `LoadingIndicator`,
  `UiLanguage`, `WhileSubscribedTimeout`.

### 7.4 Messages

One-shot messages have one path. A `MessagesViewModel` sends `UiMessage`s into an unbounded channel
with `message` or `launchReporting`; `forEachReporting` and `countReporting` report bulk writes; the
screen's one `SnackbarMessages` takes them one at a time, as each gets its turn, and shows them in
order, so what has not shown yet stays with the viewmodel when the screen goes. An Undo
(`undoAction`) is the one exception: a newer one supersedes the Undo still offered, so Undo always
acts on the latest write. A message's `UiText` names a
whole-sentence resource and its arguments and is resolved in the composition's resource environment,
so it follows the app's language. Never raise a snackbar from a composable.

### 7.5 Navigation

Routes are in `nav/Route.kt`, their composables in `nav/MainNavGraph.kt`. Every navigation a screen
starts runs through `nav.from(entry) { … }`, only while its entry is on top, so a double tap
navigates once. A `TopLevelDestination` is on the rail on wide layouts and in the bottom bar or its
More sheet on compact ones; its label is the window title, and other screens name theirs in
`windowTitle`. The assistant is one shell item (`AssistantDestination`) over two routes, the chat
and bulk edit, with a segmented control between them. Activity (`Route.Queue`) carries the shell's
one background-work indicator; work blocked on a fix (Auto-organize with no assistant) is not
counted as running, and Activity shows it as one problem row per `WorkBlock`. The command palette
(`ui/palette`, Ctrl/Cmd+K) is an overlay `MainNav` hosts over every screen: its viewmodel lives in
the library's store, searches through `DocumentRepository.searchDocuments` (bounded), runs a choice
through the screens' own navigation and facades, and reports on the shell's snackbar. On desktop `?` and Ctrl+/ open the keyboard shortcut list
(`ShortcutsDialog.kt`), which names the keys the screens bind: a key added to a screen goes there too.

### 7.6 Resources

`commonMain/composeResources/values/` (English) plus `values-nl`, `-de`, `-fr`, `-es`, `-pt`.
`checkLocalization` fails on a missing key, a placeholder mismatch or an unescaped Compose
placeholder. `SettingsSearchCoverageTest` fails on a visible settings label search cannot find.

## 8. Platform shells

**`androidApp`** adds the Android-only features: `MlKitDocumentScanner`; `IntentImport` (share-sheet
import, reported as soon as it is done; the activity imports its launch intent once, and again only
when a process death cut that import short, see `LaunchImports`); `NpnWork` (WorkManager: the
periodic watched-folder sweep, rescan and queue drains, and the one-time drain of what a share import
queued). The debug build is a separate package, `io.github.jeroenev.NoPapersNeeded.debug`.

**`desktopApp`** runs one instance per user: `SingleInstance` holds a lock file in the app data
directory, and a second launch only says so and exits. Closing the window closes the session,
bounded by a timeout; `closeThenExit` exits even when the close fails, and logs it. `DesktopBridge`
(in `composeApp`) carries the window's two ties to the shared UI: the title the screens publish and
files dropped on the window, which `AppViewModel` imports like any other import. Packaging is in
DEVELOPMENT.md.

## 9. Testing architecture

| Where | What runs there |
|---|---|
| `core/src/commonTest` | Tests of platform-agnostic core code, compiled for both targets. `FoldConvergencePropertyTest` lives here. |
| `core/src/desktopTest` | The bulk of core's tests, on the desktop JVM, including every test over a real session. |
| `core/src/desktopTest/.../sim` | The library simulation (docs/SIMULATION.md): two to four replicas of one library, real sessions over in-memory folders with a seeded sync tool between them, on virtual time, checked against invariants, a rebuild of each replica's index and each other. CI runs ten seeds in `:core:desktopTest`; `simulation.yml` runs a long run of fresh seeds on demand. |
| `core/src/androidHostTest` | Tests of `androidMain`-only code that needs no framework implementation. |
| `composeApp/src/commonTest` | Viewmodel tests over `core-testing`'s fakes, asserting on `UiText`. |
| `desktopApp/src/test`, `buildSrc/src/test` | The single-instance lock; the build guards. |

Rigs and seams in `core/src/desktopTest`: `AppSessionRig` (`AppTestSupport.kt`) opens a real session
over a temp directory through `jvmSessionDeps`; `IndexTestSupport` builds an index without a session;
`FileSystemFaults` fails publishes, moves and deletes at chosen points; `AiRuntime`'s `transport` lets
a test see every endpoint call. `core-testing` holds the facet fakes both suites share
(`FakeDocumentRepository`, `FakeTagRepository`, `FakeFieldRegistry`, `FakeMetadataWriter`,
`FakeDocContentFacade`, `FakeAiSuggest`, and `SessionDepsCopy` for copying test deps); `composeApp`'s
`commonTest` adds `FakeLibrarySession` and `FakeSessionFacets.kt` over them.

Rules for tests, from AGENTS.md: never a live endpoint call in a normal test; wall-clock time only
through the injected `EpochClock`; cancellation never swallowed.

## 10. Where to look for common tasks

- **A new synced library setting** → a `setting-set` key in `LibrarySettingKeys`, read through
  `JournalCoordinator.settingFlow(key)` and written with `putSetting` under `SessionLifetime.owned`,
  behind a facet in `LibrarySettings.kt` (`JournalTrashRetention` is the model). `SessionGraph`
  builds the facet, `LibrarySession` exposes it, `FakeLibrarySession` gets a fake. A setting older
  builds kept per device is read into `LegacyLibrarySettings` and adopted once.
- **A new document field** is data: define it in Settings → Documents or the panel's "New field…".
  Every editor, filter, sort, storage-path placeholder, auto-match action, XMP entry, AI request and
  assistant tool follows the registry.
- **A new field type** changes the three files `checkFieldTypeBranches` allows (section 6.8) plus
  the capabilities the enum constant declares. Older builds refuse a `field-def` of a type they do
  not know, so a build that reads the type ships before any build lets a user pick it.
- **A new per-document system marker** → a `SystemProperty`, written as `prop-set`, read from the
  fold (`FoldedState.effectiveProp`) by `FoldProjection.kt` into a new `DocumentEntity` column (an index schema bump), a raised
  `PROJECTION_VERSION`, its entry in `HistoryEntry.ofProperty`, and the matching contract file. A
  build that reads it ships before any build writes it.
- **New persisted state** → by who could rebuild it: library data every device must agree on goes
  in the journal; anything the folder and journal recreate goes in the index; what only this device
  knows goes in `DeviceDatabase`, with a real migration.
- **A new screen** → a package under `app/ui/`, viewmodel and screen in `commonMain`, a route in
  `nav/Route.kt` and its `composable<Route.X>` in `nav/MainNavGraph.kt`.
- **A new setting** → a control in its pane (`app/ui/settings/*Pane.kt`) from the `ui/common` form
  blocks, and its label among its category's `keywords` in `SettingsCategory.kt`.
- **A new value or action in the metadata panel** → a field on `MetadataUiState` (assembled in
  `DocDetailViewModel.metadata`), or a member of `DocumentWrites` or `MetadataActions`
  (`MetadataPanelState.kt`). A new document action → `DocumentActions` in `DocActions.kt`. Both
  hosts of `DocumentView` pick it up.
- **New platform behaviour** → an interface in `core` commonMain, an implementation per platform
  source set, wired in `jvmSessionDeps` / `androidSessionDeps`, or in `AppEnvironment.*` when it
  belongs to the app rather than a library.
- **New background work** → a queue table plus a drain step, never a `launch` at the point of
  discovery; the queue kicks its own drainer (section 6.11). Showing it to the user →
  `WorkQueueReader` behind `WorkFacade.queue`.
- **A new starting point for a bulk AI edit** → an `EditTask` constant plus its `preset` in
  `app/ui/ai/AiEditTask.kt`; nothing in `core` knows about tasks. Why the page refuses to start →
  `AiEditUiState.startBlocker`, rendered by `RunBar`.
- **Follow-up work after OCR** → `OcrPipeline.onTextProduced` and `onOcrProduced`, which fire only
  for text this device produced, never for a re-index (section 5.4).
- **Who may spend on AI unattended** → `UnattendedAiPolicy`. When the paid OCR engine runs for a
  document → `OcrEngineSelector` (section 6.10).
