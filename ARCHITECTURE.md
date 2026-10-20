# Architecture

How the codebase is organized, how data flows through it, and the rules each part keeps. Read
this first. The other documents each own one thing and this one does not repeat them:

| Document | Owns |
|---|---|
| AGENTS.md | Build and test commands, conventions, detekt, and the short list of invariants you must not break. |
| CONTEXT.md | The domain glossary: what Field, Edit Run, Review, facet and so on mean here. |
| DEVELOPMENT.md | Design background, packaging and release mechanics, the library folder layout on disk. |
| `docs/` | Longer notes on one topic each: `SIMULATION.md` (the deterministic simulation of the core), `AI_TOOL_DATA_GAPS.md`. The directory is otherwise ignored by git, for local working notes; a document meant for the repository is listed in `.gitignore`'s exceptions. |

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
| `device.db` | What only this device knows and nothing could rebuild: its journal device id, the OCR and AI queues, enrichment marks, chats. Real migrations only. | Truth, device-owned | Device-local, beside the index | Never |
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
`core.db.NpnDatabase`. Android picks its filesystem from the persisted location string
(`core.settings.LibraryLocations`): a plain path opens the shared direct filesystem, which is the
default and what Syncthing interoperability relies on; a `content://` tree URI opens
`SafLibraryFileSystem`, the advanced option for cloud document providers.

## 3. Core package map

One line per package. The rules each package enforces are in section 6.

| Package | Contents |
|---|---|
| `core.app` | The public session API and the orchestrators behind it. One contract file per concept: `LibrarySession.kt`, `Documents.kt`, `History.kt`, `Metadata.kt`, `FieldRegistry.kt`, `Ingest.kt`, `Ocr.kt`, `LibrarySettings.kt`, `Maintenance.kt`, `WorkFacade.kt`, `DocContentFacade.kt`, `Filters.kt`. Implementations: `LibrarySessionImpl`, `SessionGraph`, `SessionOpening.kt`, `MetadataWriterImpl`, `DocLifecycleImpl` (with its pure `LifecyclePlan`), `OcrFacadeImpl`, `MaintenanceFacadeImpl`, `SessionWork`, `JournalReviewConfig`, `BakeRelink`. |
| `core.app.aiedit` | The AI edit engine over the facets: `AiEditRun`, `AiEditQueue`, `AiAutoEnricher`, `AiEditReview.kt` (`ReviewProjector`, `ReviewApply`), `SuggestionRunner`. |
| `core.journal` | The metadata journal: `JournalEvent`, `JournalOp`, `PendingOp`; `ContentAddressedJournalWriter`; `JournalPaths`; the readers for v2 objects, snapshots and legacy v1 segments (`JournalSourceReaders`); `JournalCodec`; `Hlc`, `HlcWatermarkAllocator`; `SnapshotFormat` (the compaction snapshots earlier builds wrote, read and never written); `EventCopies` (two copies of one event); the fold, `FoldedState`. |
| `core.index` | The rebuildable index and its maintenance. The executor: `JournalCoordinator` and its state machine `WriterState`. The pure decisions it and its helpers carry out: `RescanDecision`, `FoldLifecycle`, `ImportDecision`, `PassDecision`. The effects: `Indexer`, `MetadataAccumulator`, `FoldProjection`, `SourceSetValidator`, `ImportService`, `DuplicateResolver`, `SplitService`, `ScanReport`, `DeviceIdentity`, `DocumentPathReconciler`, `ExternalDocumentMoves`. |
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
| `core.update` | The update check: `AppVersions`, `LatestReleaseSource`, `GitHubLatestRelease`. |
| `core.util` | `runCatchingCancellable`, `Throwable.reason()`, `EpochClock`, `NpnLog` and `runLogged`, `NpnJson`. |

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
rescan's resurrection of a document whose own bytes are back, and which returns are settled enough
to record), `ImportDecision` (an import's duplicate, return of a hidden document, refusal of
superseded bytes, and the row it writes), `LifecyclePlan` in `core.app` (the events, row and file
move of a trash, restore or delete for good) and `PassDecision` (how a reconcile takes what it read
into the fold, and what the projection then needs). The classes around them gather the facts (a
listing, hashes, rows, the fold) and carry the plans out; the coordinator is the one executor that
holds the mutex and mutates the session's state: the live fold, the synced settings and the
writer's state.

**The writer's state machine.** `WriterState` is `Ready` (the projection is the fold of the journal
the last pass read; writes are taken), `RefoldPending` (a pass was cut short; writes are taken, and
the next reconcile or ingest refolds and reprojects in full) or `Blocked(reason)` (a pass failed;
writes are refused with that `MetadataWriteBlocked` until a pass reads the journal whole). Every
entry point runs as one `pass`, which ends in a `PassOutcome`: `Projected` (it read the journal
whole and projected it, `adopt`) leads to `Ready` from any state; `Failed` leads to `Blocked` with
the pass's own refusal, or `RECONCILE_FAILED`; `Unfinished` (cancelled, or a richer copy of an event
only a full refold takes in) leads to `RefoldPending` and leaves a block standing. Every outcome
but `Projected` drops the live fold. A pass of a write that is durable already (the projection of a
commit, an ingest) fails into `Blocked` and rebuilds the projection from the store at once, which
lifts the block when it succeeds; its cancellation changes nothing.

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
  which by then may be a file a sync tool put there.
- A file a user moved outside the app is journaled as the document's new `storagePath`
  (`ExternalDocumentMoves`), once this device saw it settled at its old path under the intent it
  still holds. A file at a path a `storagePath` event already named for the document, winning or
  overruled (`RescanDecision.externalMoveCandidates`), is never such a move: it is a sync tool's
  mirror of some device's move, and journaling it would set an older place against the newest
  intent, so two devices would move the document back and forth for good. The cost: a file a user
  moves back to a place the journal named before goes to its intended path again.
- Storage that cannot move a document (SAF, `supportsDocumentRelocation` false) refuses a rename
  or move with `RelocationOutcome.UNSUPPORTED` and writes nothing; a rule's location is still
  journaled there, once, for a replica that can carry it out.

### 6.2 Import and duplicates

Kept by `ImportService`, which carries out what `ImportDecision` decides:

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

### 6.3 Trash, restore and delete for good

Kept by `DocLifecycleImpl` (its plans in `LifecyclePlan`), `TrashOps`, `JournalCoordinator.changeLifecycle` and
`TrashPurge`:

- `doc-trash`/`doc-restore` and `doc-delete`/`doc-undelete` are last-writer-wins pairs by HLC; an
  undelete also takes the document out of the trash.
- Trash, restore and delete for good read the row, check the file and move it under the writer lock,
  in one step with their journal write, so no rescan or storage-path move lands in between.
- A trash moves the file at the document's path only while it is the document's own (stored size
  and mtime, else its hash). A file written over it since the last rescan stays, and the trash is
  recorded as for a document whose file is gone.
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
  its file name when that is no document path), or a `(restored)` name beside it when a file is
  there now or another live row names it.
- The trash auto-purge runs after a watch-triggered rescan. It lists the trash before the writer
  lock and deletes an entry under it, in batches, once every document its name's hash prefix ties it
  to was trashed or deleted longer ago than the retention, by the journal and the rows as they
  stand there. The retention is the library's (`TrashRetentionFacade`, a synced setting); one this
  build cannot read purges nothing. An entry it cannot tie to such a document stays, however old.

### 6.4 Rescan, settling and resurrection

Kept by `Indexer` (its decisions in `RescanDecision` and `FoldLifecycle`), `WriteSettle`,
`JournalCoordinator.recordRestores` and `OcrPipeline`:

- A rescan hashes a PDF only once its bytes have settled (`WriteSettle`). Files written in place
  are deferred and picked up by a follow-up rescan, whichever rescan deferred them; a relink or a
  bake hands the report of the rescan it ran to the session, which follows it up as its own.
- A stat proves a file's bytes only with a real mtime (`FileMeta.hasStoredStat`, the one place every
  proof by stat goes through: the rescan's fast path, an import's duplicate check, `holdsDocument`,
  the trash and duplicate removal). Where storage reports no mtimes (a SAF provider giving 0), a file
  of the same size may be other bytes, so it is hashed: such a library hashes every PDF at every
  rescan.
- `OcrPipeline` re-checks the hash of the bytes it read before publishing a sidecar, so a partial
  file never gets a document identity. A read that finds other bytes asks for a follow-up rescan
  and leaves its run queued, uncharged; a second one in a row counts as a failure.
- A rescan skips a file it cannot read rather than failing, and treats a failed read as not held.
- When the bytes of a trashed or deleted document come back, the return must be recorded in the
  journal, or the next fold hides the document again. An import records it at once (section 6.2).
  A rescan that finds the file back writes it once the event that hid the document is a day old
  (`RESTORE_SETTLE_MS`), as the scan's last step; sooner, the file may be one a sync tool has not
  taken away yet, and the rescan shows it for that scan only. A failed write is logged and left to
  the next rescan.
- The file wins only with the document's own bytes: the rescan must know the hash of the file at
  the document's path, or at the path it was trashed from when no other row holds that, and the
  hash must be the document's. Another file under the same name, or a file still being written,
  brings nothing back.
- `doc-undelete` is newer than the other lifecycle ops. This build reads and folds it but does not
  write it (`JournalOp.DocUndelete.WRITTEN` is false; the coordinator's `writeUndelete` defaults
  from it), so a build from before the op never meets one. Until it is turned on, a deleted
  document's return records nothing: an import of its bytes stands until a fold hides it again, a
  rescan shows its file for that scan only, and a delete for good that still removes the bytes
  leaves such an import's file in place, since the coordinator remembers, for the session, which
  deleted documents an import brought back unrecorded. A trashed document's return still writes
  `doc-restore`.

### 6.5 Relink and bake-in-place

Kept by `BakeService`, `BakeRelink`, `DocContentFacadeImpl` and `FoldedState`:

- Bake-in-place replaces only the bytes it read and hashed as the document's, while the file keeps
  the size and mtime it had then, and records a `relink` journal event for the new hash. Bakes run
  one at a time, each whole, under the session's `BakeLock`; every bake of a path writes the same
  temporary file, and its relink must name the bytes it installed. A trash or delete for good holds
  that lock too, so it waits for a bake and then acts on the document the bake made
  (`changeLifecycle` follows the relink).
- **A document's metadata is its own.** Every view the fold projects reads the document's own
  events, a relink target's included. A relink (a bake, or a missing document relinked to the file
  that took its place) writes, in the same transaction as the `relink` op, ordinary `field-set`,
  `prop-set` and `tag-add` copies of the old document's effective metadata onto the new one
  (`FoldedState.relinkOps`, `JournalCoordinator.relinkAndRescan`): every field value, every system
  property (storage path, review mark, OCR engine pin) and every live tag, a value of the old
  document's replacing the new one's and their tags joined. The relink op itself only supersedes:
  the old document is hidden (`resolveDocId`, `isCurrent`) and its bytes are a duplicate of the
  target while the library shows it (section 6.2).
- So an edit of the old document made after the relink, by a device that had not seen it, no
  longer reaches the target, nor does a tag removal from it; such an edit is one the device that
  has the relink cannot see or make. A document's history is its own events, so a relink target's
  shows the copies, not its predecessors' edits.
- Libraries an earlier build relinked in, without copies, get them once at open
  (`RelinkInheritanceUpgrade.kt`, the one place that still computes the inherited view, guarded by
  the synced `relinkCopies` setting; a relink carries the upgrade too while it has not run). The
  upgrade accepts one race: a device that upgrades before an edit to a predecessor reached it
  copies the older value, which wins when its HLC is later.
- A bake changes no page, so its relink hands the original's text on: before it, the original's OCR
  versions are published again under the baked hash as this device's (`OcrPipeline.copyText`) and
  its enrichment mark is copied; after it, that text is indexed, so the baked document is
  searchable at once and is neither OCR'd nor enriched again. A relink to other bytes hands on the
  copied metadata and nothing else.

### 6.6 Journal storage

**Formats.** Every transaction is one storage-v2 object,
`.npn/journal/objects-v2-<xx>/<sha256>.jsonl`, named by the SHA-256 of its bytes, with a fresh
actor id per transaction. Builds before storage-v2 wrote per-device v1 segments,
`.npn/journal/<deviceId>/<seq>.jsonl`; those are read and folded, never written. There is no
fallback format. Beside its objects a bucket may hold their staging files and a sync tool's file for
one still in transfer (`NpnPaths.isSyncTemp`: Syncthing, Nextcloud and ownCloud, Resilio), which a
reconcile passes over and never deletes; any other name there fails the pass. A staging file a
crash left behind, on any installation, is the rescan's `TempFileSweep`'s to delete once a day old.

**Publication.** An object is staged under a `.part` name in its bucket, hash-checked, then
published without replacement: a hard link, or where the filesystem has none (Android shared
storage, exFAT) an exclusive rename (`renameat2(RENAME_NOREPLACE)` on Linux and Android,
`MoveFileExW` without `MOVEFILE_REPLACE_EXISTING` on Windows), or on SAF a rename checked by name
and hash afterwards, in a bucket whose directories carry exactly their names. A filesystem with
neither primitive throws `VerifiedPublicationUnavailableException`, and the library refuses to open
rather than write anything. `stageAndPublishVerifiedObject`, beside `ContentAddressedJournalWriter`,
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
a journal holding those events again lifts the block; there is no grace window, since objects are
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
at every open (SAF skips the probe: `LibraryFileSystem.publicationMayBeLost`).

### 6.7 Fold and projection

Kept by `FoldedState`, `MetadataAccumulator`, `FoldProjection`, `Indexer`, `PassDecision`:

- The fold is OR-set tags and HLC last-writer-wins registers; `FoldConvergencePropertyTest`
  property-tests its convergence.
- The projection is a pure function of the applied source set. `MetadataAccumulator` fingerprints
  that set beside the ready marker: an open whose fingerprint still matches reuses the stored
  projection instead of refolding. **Changing what a fold produces from the same events requires
  bumping `MetadataAccumulator.PROJECTION_VERSION`**, or existing installs keep rows built by the
  old fold.
- Field values land in `document_fields` (one row per document and key, canonical string plus a
  numeric shadow for NUMBER and DECIMAL) and definitions in `field_defs`; the five typed columns on
  `documents` are a read cache written from the same map, never a second source. One FTS `fields`
  column indexes every value; a document's FTS row is reached through `fts_keys`; the fold writes
  its metadata columns and `OcrPipeline` its text, and neither rewrites the other's.
- `LIVE_DOCUMENT` in `core.db` is the one SQL predicate for what the library shows.
- A pass that failed or was cut short drops the fold and leaves the writer `Blocked` or
  `RefoldPending` (`WriterState`, section 4.3), so the next reconcile or ingest refolds and
  reprojects in full (`recoverDurableProjection`) rather than build on what it had. A reconcile
  extends the live fold only with events it purely added; a wiped projection, a mutated source or a
  richer copy of a stored event folds again in full (`PassDecision.foldStep`).
- A journal source is re-read only when its size or mtime moved; one applied before is then
  recorded again with its new fingerprint, which a reconcile pays for with a full refold even when
  only the mtime changed. A reconcile pass that holds a fold marks the projection reconciling until
  the coordinator marks it ready again, so a pass cut short leaves the next open refolding.

### 6.8 Fields are data, not code

- Title, correspondent, docType, docDate and notes are ordinary `field-set` fields whose definitions
  the library seeds (`FieldKeys`); there is no per-field Kotlin. A change to the seeds raises
  `FieldKeys.SEED_VERSION`, and `FieldKeys.upgradeSeeded` brings older libraries up once, never
  overriding what their users changed.
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
- A storage-path template that cannot expand says why with a `StoragePathError`; its context always
  carries the registry's field types, which decide which fields take a date pattern.
- A watched inbox folder at or inside the library (`liesInLibrary`) is never swept, and Settings
  refuses one when picked. Delete-after-import removes a source only while it still holds the bytes
  imported: while it has the size and mtime it was listed with, or, from a provider that listed no
  mtime, while it hashes to the document.

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

## 7. UI architecture (`composeApp`)

### 7.1 Shell and state ownership

`App.kt` and `AppViewModel` open, restore and switch the library behind one sealed `AppState`
(`Restoring`, `Opening`, `NoLibrary`, `RestoreFailed`, `Open`), which also carries every failure to
open as a localized `OpenFailureReason` with the technical cause as an optional detail.

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
entry saves, so the view showing it saves its handle (`rememberDocumentSavedState`). State the
screens share or hand each other belongs to `LibrarySessionUi`: the drafts (saved at the root, for
a restored process) and the hand-offs (not saved; a viewmodel that needs one after a restore, as AI
edit does its documents, saves its own copy). A modal dialog's own edit buffer (the text in a
`TextInputDialog`) may stay in the dialog's `rememberSaveable` while the viewmodel holds which
dialog is open, as long as the dialog keeps its place across layouts: a language change cannot
happen under a modal, and content that changes place with the width class moves as one
`movableContentOf` (the navigation content, a document's metadata tabs). What a composable may keep
is what nobody misses: hover, focus, an open menu, animation, a gesture under way, a splitter or
scroll position. What a restored process legitimately loses is work in flight (a rescan, a model
call, a write), messages not yet shown, and what lived only in memory (results fetched on demand,
the last scan's report). Every viewmodel test recreates its viewmodel at least once mid-interaction
through `Recreatable` (`composeApp` commonTest), which saves the handle as a process death does,
builds a new viewmodel from it and the same fakes, and checks the screen renders the same; its
`Transient` lists the exceptions, each with why.

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
`trash/`, `queue/`, `onboarding/`, `ai/`, `update/`, `nav/`, `common/`, `theme/`. Viewmodels sit
beside their screen.

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
  `ActionMenuItems`, `keyShortcuts` (the one way a screen binds keys), `EmptyState`, `TagEditor`,
  `TagFormDialog`, `TagPickDialog`, `DocThumbnail`, `ConfirmDialog`, `LoadingIndicator`,
  `UiLanguage`, `WhileSubscribedTimeout`.

### 7.4 Messages

One-shot messages have one path. A `MessagesViewModel` sends `UiMessage`s into an unbounded channel
with `message` or `launchReporting`; `forEachReporting` and `countReporting` report bulk writes; the
screen's one `SnackbarMessages` shows them one at a time, in order. A message's `UiText` names a
whole-sentence resource and its arguments and is resolved in the composition's resource environment,
so it follows the app's language. Never raise a snackbar from a composable.

### 7.5 Navigation

Routes are in `nav/Route.kt`, their composables in `nav/MainNavGraph.kt`. Every navigation a screen
starts runs through `nav.from(entry) { … }`, only while its entry is on top, so a double tap
navigates once. A `TopLevelDestination` is on the rail on wide layouts and in the bottom bar or its
More sheet on compact ones; its label is the window title, and other screens name theirs in
`windowTitle`.

### 7.6 Resources

`commonMain/composeResources/values/` (English) plus `values-nl`, `-de`, `-fr`, `-es`, `-pt`.
`checkLocalization` fails on a missing key, a placeholder mismatch or an unescaped Compose
placeholder. `SettingsSearchCoverageTest` fails on a visible settings label search cannot find.

## 8. Platform shells

**`androidApp`** adds the Android-only features: `MlKitDocumentScanner`; `IntentImport` (share-sheet
import, reported as soon as it is done; the activity imports its launch intent once, and again only
when a process death cut that import short, see `LaunchImports`); `NpnWork` (WorkManager: the
periodic watched-folder sweep, rescan and queue drains, and the one-time drain of what a share import
queued). The debug build is a separate package, `dev.npn.debug`.

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
| `core/src/desktopTest/.../sim` | The library simulation (docs/SIMULATION.md): two to four replicas of one library, real sessions over in-memory folders with a seeded sync tool between them, on virtual time, checked against invariants, a rebuild of each replica's index and each other. CI runs ten seeds in `:core:desktopTest`; `simulation.yml` runs fresh seeds nightly. |
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
