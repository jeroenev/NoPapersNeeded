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
| `core.app` | Public session API and orchestrators, one contract file per concept: `LibrarySession.kt` (the aggregate of facets), `Documents.kt` (`DocumentRepository`, `TagRepository`, `SavedViewRepository`), `Metadata.kt` (`MetadataWriter`, `TagAdmin`, `DocLifecycle`), `Ingest.kt`, `Ocr.kt`, `ReviewConfigFacade.kt`, `Maintenance.kt`, `DocContentFacade.kt`, `Filters.kt`, `Settings.kt`. Consumers depend on the facets they use, not on `LibrarySession`. `LibrarySessionImpl` coordinates imports/indexing and is the main entry point. `JournalCoordinator` is the only sanctioned path for writing journal events. |
| `core.journal` | The metadata journal: content-addressed JSONL transactions (`JournalEvent`, `JournalOp`), segments (`Segment`, `SegmentWriter`), codec (`JournalCodec`), HLC (`Hlc`), snapshot compaction (`SnapshotCompactor`, `SnapshotFormat`), and the HLC/OR-set fold (`FoldedState`, `FoldTest`). |
| `core.fs` | Filesystem abstraction over the library folder: `LibraryFileSystem` (platform impl), `NpnPaths` (`.npn/` layout), `ContentIdentity`/`HexEncoding` (hash handling), `OcrSidecarFiles`, `TrashOps`. |
| `core.index` | The rebuildable SQLite cache and maintenance: `Indexer`, `ImportService`, `MaintenanceService`, `RelinkService`, `SplitService`, `MetadataAccumulator`, `FtsWriteGate`, `DbLocalJournalState`, `DeviceIdentity`, `ScanReport`. |
| `core.db` | Room entities and DAOs (`NpnDatabase`, `DocumentDao`, `TagDao`, `FtsDao`, `MetadataProjectionDao`, `MiscDaos`, `Projections`). Schema files in `core/schemas`. Platform `NpnDatabase.android/desktop.kt` build the instance. |
| `core.pdf` | PDF rendering/thumbnails: `PdfEngine` (platform impl), `RenderGate`, `ThumbnailService`. |
| `core.ocr` | OCR pipeline and sidecars: `OcrEngine`/`OcrEngines` (platform impl), `OcrPipeline`, `OcrSidecar`, `OcrWorker`. |
| `core.xmp` | XMP metadata on export/bake-in-place: `PdfXmpIO` (platform), `XmpBytes`, `XmpParser`, `XmpSerializer`, `XmpMerger`, `NpnXmpData`, `BakeService`. |
| `core.model` | `Document` domain model. |
| `core.rules` / `core.pathrules` | Auto-match rules (`RuleMatcher`, `RuleApplier`, `AutoMatchRule`) and storage-path templates (`StoragePathRule*`, `StoragePathContext`). |
| `core.watch` | Watched inbox folder: `WatchedFolder` (platform), `WatchedFolderImporter`. |
| `core.ai` | Local/AI chat loop and tools over the library (`AiChatLoop`, `AiChatTool`, `AiFacadeImpl`, `LibraryAiTools`). |

Platform impls live in the same package under `androidMain`/`desktopMain` (e.g.
`core.fs.LibraryFileSystem`, `core.pdf.PdfEngine`, `core.ocr.OcrEngines`,
`core.db.NpnDatabase`).

### `composeApp` — `dev.npn.app.*`

Shared Compose Multiplatform UI + viewmodels + dependency wiring.

| Path | Contents |
|---|---|
| `app/App.kt` | Root composable / app shell. |
| `app/di/` | `AppEnvironment` (session lifecycle: open/close/swap, watched folder) + `DefaultAppEnvironment`. `PlatformLibraryFactory`/`WatchedFolderFactory` are fun interfaces the platform source sets implement; `AppEnvironment.android/desktop.kt` build the real per-target pieces. |
| `app/platform/` | Small shared platform hooks. |
| `app/ui/` | Screens, one package each: `library/` (list/grid, filters, preview), `detail/` (page viewer, metadata panel, history, split), `tags/`, `rules/`, `settings/`, `trash/`, `onboarding/`, `nav/` (shell + routes), `ai/`, `common/` (shared components: `TagEditor`, `DocThumbnail`, `SearchField`, `MessagesViewModel`, `SnackbarMessages`, `UiText`), `theme/`. ViewModels are co-located with their screen (e.g. `LibraryViewModel`, `DocDetailViewModel`). |
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
2. **Journal is source of truth for metadata**: every change (tags, properties,
   trash, relink) is an immutable, content-addressed JSONL transaction written by
   `JournalCoordinator`. Never write to the journal directly.
3. **Index is a cache**: `Indexer` folds journal state (OR-set tags, HLC LWW) into
   the Room database (`FoldedState`, `MetadataAccumulator`). The db lives
   device-local, outside the library folder, and is rebuildable from the folder.
4. **UI reads the cache**: viewmodels subscribe to Room DAO Flows (with the named
   `WhileSubscribed(5_000)` constant) and map rows into UI models via the
   `core.app` facet interfaces; the Compose layer is a pure renderer of those.
5. **Platform boundaries**: every engine (`PdfEngine`, `OcrEngine`,
   `LibraryFileSystem`, Room db, `WatchedFolder`) has a common interface with
   per-target implementations wired in `AppEnvironment.android/desktop.kt`.

## Where to look for common tasks

- Add a new property to a document → journal event + `FoldedState` + `Projections`
  + the matching `core.app` contract file (`Metadata.kt` for a write, `Documents.kt`
  for a read) + UI model.
- New screen → package under `app/ui/`, viewmodel + screen in `commonMain`,
  route in `app/ui/nav/Route.kt`.
- New platform behavior → interface in `core` commonMain, impl in each platform
  source set, wire in `AppEnvironment.*`.
- Background work → `core.watch` importer loops, `NpnWork` (Android), `OcrWorker`.
