# No papers needed - implementation plan

Personal, open-source paperless document solution. Scan, OCR, tag, and search PDFs stored in a plain folder that syncs externally (syncthing, cloud clients). Documents are immutable; metadata lives in per-device write-once journal segments inside the library, making sync conflicts on app-written data structurally impossible. Tags can be baked into PDFs as XMP on export or on demand.

Decisions taken (2026-07-15):
- Framework: Compose Multiplatform (Kotlin Multiplatform)
- v1 platforms: Android + Linux desktop; Windows/macOS packaging deferred to v2 (same JVM target, mainly packaging/testing work)
- v1 sync: syncthing-style watched folder (app is sync-agnostic)
- Metadata: per-device write-once journal segments in the library folder (conflict-free); PDFs immutable; SQLite as rebuildable index; XMP baking on export/share plus manual bake-in-place action
- Licensing: open source, no billing
- Distribution: GitHub releases primary (MLKit build). Scanner/OCR behind interfaces so a FOSS flavor (F-Droid main repo compatible) can be added later; IzzyOnDroid possible in the meantime
- Duplicates are forbidden in the library: import dedups by hash; externally introduced duplicates are flagged for cleanup. Every hash has exactly one canonical path
- Clocks: hybrid logical clocks (HLC) for last-writer-wins, not pure Lamport
- OCR text syncs as sidecars in `.npn/ocr/` so devices adopt each other's results instead of re-OCRing
- v1 scope: kanban stays; auto-tagging rules move to post-v1

## 1. Architecture principles

1. **Files are the source of truth.** The library is a folder of PDFs plus a small `.npn/` metadata directory. Any device with the folder has everything. The app must be able to rebuild all state (index, search, views) from the folder alone.
2. **Documents are immutable.** Once a PDF lands in the library, the app never modifies its bytes (rename/move only). No corruption risk, no sync churn on metadata edits, stable content-hash identity. The only exception is the explicit, user-invoked bake-in-place action.
3. **Metadata is conflict-free by construction.** Each device writes only new, write-once segment files under its own journal directory. No app-written file is ever modified after close or written by two devices, so no sync tool can produce a conflict on app data, regardless of offline duration. The one remaining concurrency is file moves/renames (trash, user rename) on shared PDF paths; sync tools resolve those without data loss and the indexer tolerates the resulting resurrections or duplicates.
4. **The database is a disposable cache.** SQLite (Room) is a materialized view of files + journals: search index, thumbnails, OCR text. It lives in app-local storage, never inside the library folder, and is never synced.
5. **Sync is externalized.** The app never talks to a network for sync. It watches/rescans the folder and reacts to external changes.
6. **Metadata can travel with the document when needed.** XMP baking (on export/share, or manual bake-in-place) embeds tags and properties into PDFs for interoperability with other tools.
7. **Everything works offline.** OCR is on-device (MLKit on Android, Tesseract-based on desktop). Future AI tagging is local (Gemma).

## 2. Document model

### Identity
- Document identity = SHA-256 of file bytes. Because documents are immutable, the hash is stable forever: renames and moves (by the app or externally) never detach metadata.
- Fast-path change detection during rescans: path + size + mtime; hash computed once per new/changed file and cached. Initial indexing of a large library is a full read of every file (slow over SAF); it runs as a background job with visible progress.
- **Duplicates are forbidden.** Import checks the hash first: an identical file is skipped with a notice (optionally updating the existing document's location). If external action creates a duplicate (user copies a file into the folder), the indexer flags it in the maintenance screen and offers to remove the extra copy. Every hash therefore has exactly one canonical path, so file-level operations (trash, rename, move) are unambiguous.
- Edge case: a PDF edited by an external tool (annotation, bake by another app) changes hash and appears as a new document. Mitigation: relink flow that pairs a disappeared hash with a new file (same path/name heuristics, size + page-count fingerprint stored in the index, or an embedded `npn:documentId` if the file was ever baked) and offers to carry metadata over via a relink journal event.

### Metadata journal (per-device, write-once segments)
- Location: `.npn/journal/<deviceId>/<seq>.jsonl` inside the library folder, so it syncs with the documents. Each device only ever creates new segment files in its own directory; a segment is written once, closed, and never modified. All devices read all segments. This is the single journal design on both platforms: no append mode needed, which sidesteps SAF's unreliable append semantics entirely.
- A segment is opened per session (or per size/time threshold) and contains JSON-line events: `{seq, hlc, deviceId, docId, op, payload}`. Ops: `tag-add`, `tag-remove`, `prop-set` (status, correspondent, document type, document date), `doc-trash`, `doc-restore`, `doc-delete` (tombstone), `relink`, plus `tag-rename`/`tag-merge` as library-wide ops.
- Merge semantics, deterministic on every device:
  - Tags: observed-remove set (OR-set). Each `tag-add` is identified by its dot `(deviceId, seq)`; a `tag-remove` lists the dots it has observed. An add wins over a concurrent remove that hasn't seen it; re-adding after remove works correctly.
  - Single-value properties: last-writer-wins ordered by hybrid logical clock (HLC: physical time + logical counter), device-id tiebreak. Deterministic like Lamport, but recent edits win as users expect even after long offline periods. Absurd wall-clock values are clamped against the local clock to bound damage from a misconfigured device.
  - `tag-rename`/`tag-merge` are ordered by HLC against tag-adds: an add of the old name that is concurrent with or after the rename follows the rename (deterministic on every device).
  - Concurrent `relink` events claiming the same old docId (e.g., two devices baked the same file offline): deterministic winner by HLC + device-id; the loser's file is imported as a new document with metadata copied over.
- Current state = fold of all segments, cached in SQLite (rebuildable at any time). Folding is incremental: the index tracks which segment files have been processed (segments are immutable, so a filename either is or isn't folded; no byte offsets, no invalidation problem).
- Compaction: a device may merge its own closed segments into a single snapshot segment and delete the originals; other devices' directories are never touched. The snapshot must preserve live OR-set dots, the HLC of each winning property write, and tombstones (they're tiny), so folding the snapshot yields the same state as folding the originals. Other devices notice the segment set changed by listing filenames and refold that device's directory.
- Device identity: `deviceId` is an install-generated UUID. On startup the app verifies the newest segment in its own directory is one it wrote (tracked locally); if not, another installation shares the id (backup restore, cloned device) and the app forks a fresh deviceId. This preserves the single-writer invariant.
- Journal files are excluded from the document index and hidden from the library UI.
- Why this is conflict-free: sync conflicts require two writers on one path. Segments are write-once and single-writer by design, PDFs are write-once, so app-generated conflicts cannot occur on syncthing or any cloud client.

### XMP baking (portability escape hatch)
- Bake = write `dc:subject` (Dublin Core keywords, read by exiftool/Zotero/paperless-style tools), doc info `Keywords`, and the `npn:` namespace (`documentId`, properties) into a PDF.
- On export/share: always bakes into the outgoing copy; library file untouched.
- Manual bake-in-place: explicit user action (single doc or bulk). Rewrites the library file via temp-write + parse-verify + atomic rename, records the new hash with a `relink` event so identity follows the rewrite. Warned as a sync-heavy operation. This is the one operation that rewrites a shared file: if two devices bake the same document while offline from each other, the sync tool will surface a conflict copy of the PDF and the concurrent relinks resolve deterministically (see merge semantics); the conflict copy is handled by the conflict-file flow.
- Implementation: small XMP RDF/XML serializer/parser in commonMain; PDFBox only gets/sets the raw `PDMetadata` stream and doc info (avoids pdfbox-android xmpbox gaps). Since baking is not on the critical path, this is low-risk.

### OCR sidecars
- OCR output is shared through the library: `.npn/ocr/<sha256>.<deviceId>.json` containing extracted text per page plus engine/language metadata. Single-writer by construction (deviceId in the filename), write-once like everything else the app writes.
- Before OCRing, a device checks for an existing sidecar for that hash (from any device) and adopts it into its local index. Devices only OCR documents nobody has processed yet; a phone will usually find desktop-produced text waiting for it.
- Sidecars are compact (text only) and excluded from the document index. Orphaned sidecars (hash no longer present, past trash retention) are garbage-collected.

### Library folder layout
```
Library/
  ... user-organized PDFs and subfolders ...
  inbox/            # optional scan/import target (configurable)
  .npn/
    journal/<deviceId>/<seq>.jsonl
    ocr/<sha256>.<deviceId>.json
    trash/          # soft-deleted files (synced, excluded from index)
```
- Note: some cloud clients skip hidden dot-files by default. Syncthing syncs them fine; the sync setup guide documents per-client behavior and workarounds.
- Sync-conflict files from any tool (`*.sync-conflict-*`, Dropbox "conflicted copy", Nextcloud suffixes) should now only appear from external causes (user edits, other apps, concurrent bake-in-place). The indexer still detects them via a pattern list, excludes them, and offers keep/discard/import-as-new. No merge machinery needed for app data.

## 3. Module and tech stack

```
no-papers-needed/
  settings.gradle.kts
  gradle/libs.versions.toml
  composeApp/            # CMP UI + DI + app entry points
    src/commonMain/      # screens, viewmodels, navigation, theme
    src/androidMain/     # Android entry, MLKit scanner launcher, SAF pickers
    src/desktopMain/     # desktop entry, window/menu/tray, file dialogs
  core/                  # KMP library module
    src/commonMain/      # domain models, use cases, repository interfaces,
                         #   journal engine (events, fold, compaction),
                         #   XMP serializer/parser (bake), Room entities + DAOs
    src/androidMain/     # actual: filesystem (SAF/DocumentFile), OCR (MLKit),
                         #   PDF (pdfbox-android), thumbnails (PdfRenderer)
    src/desktopMain/     # actual: filesystem (java.nio + WatchService),
                         #   OCR (Tesseract), PDF (Apache PDFBox + xmpbox),
                         #   thumbnails (PDFBox rendering)
```

Two modules is enough for a solo project; split further only when build times hurt.

Key dependencies:
- Kotlin 2.2+, Compose Multiplatform 1.11+, targets `androidTarget()` + `jvm("desktop")`
- Room 3.x (androidx.room3) with `BundledSQLiteDriver` on both targets; the bundled driver ships FTS5 and Room 3 has an FTS5 entity annotation. Spike verifies the FTS5 entity on the JVM target; SQLDelight for the search table is the fallback only if that spike fails
- kotlinx-coroutines, kotlinx-serialization, kotlinx-datetime
- DI: keep the manual `AppContainer` pattern from OdooDrop, or Koin if wiring gets tedious (Koin is KMP-native)
- Android: `play-services-mlkit-document-scanner`, `mlkit:text-recognition` (bundled), `pdfbox-android`, WorkManager, DataStore. Scanner and OCR sit behind `DocumentScanner`/`OcrEngine` interfaces so a FOSS flavor (CameraX capture + Tesseract) can be added later without touching callers
- Desktop: Apache PDFBox 3.x + xmpbox, Tess4J or `tesseract`/`ocrmypdf` CLI detection, java.nio `WatchService`
- FileKit (or equivalent KMP file picker lib) for pick/save dialogs; verify current best option during setup

## 4. Platform notes

### Android storage
- The library must be a real filesystem folder for syncthing to sync it. Two access modes:
  1. SAF tree URI (`ACTION_OPEN_DOCUMENT_TREE`) - Play-friendly, but no reliable change notifications and slower I/O -> rescan-based indexing.
  2. All files access (`MANAGE_EXTERNAL_STORAGE`) - allows direct `File` I/O and `FileObserver`, acceptable for personal/GitHub builds, restricted on Play.
- Plan: abstract behind a `LibraryFileSystem` interface. Ship SAF as default; offer all-files mode as an opt-in for power users.
- The journal never appends, so SAF only needs create-and-write-once semantics, which are reliable everywhere.
- Indexing triggers: app foreground, pull-to-refresh, periodic WorkManager job, and after own writes. Rescan uses mtime+size fast-path so it stays cheap on large libraries.

### Android distribution
- Primary channel: GitHub releases (APK), MLKit build. MLKit document scanner requires Google Play services on the device and both MLKit libraries are closed source, which blocks F-Droid's main repo and de-Googled devices.
- IzzyOnDroid listing is compatible with the MLKit build and can be added early for discoverability.
- F-Droid main repo requires the FOSS flavor (post-v1 roadmap): CameraX capture + edge detection for scanning, Tesseract for OCR, selected via the existing interfaces.
- Sync client guidance: official syncthing-android was discontinued (Dec 2024). The sync setup guide recommends actively maintained wrappers (Syncthing-Fork on F-Droid) and is kept current, since this ecosystem has churned (repo ownership changes in 2025).

### Desktop
- `WatchService` for live folder events + full rescan on startup.
- Packaging via `compose.desktop` jpackage: `.deb`/`.rpm` + AppImage or Flatpak for Linux. Flathub submission as stretch goal (JVM apps are accepted; bundle Tesseract in the Flatpak). Windows `.msi` and macOS `.dmg` are v2 (same JVM target, packaging and testing work only).
- Wayland: CMP desktop currently renders through XWayland. Acceptable; native Wayland (JBR WLToolkit + Skiko) is progressing upstream, revisit later. Test on GNOME and KDE Wayland sessions.

## 5. Feature design

### Scanning
- Android: MLKit document scanner (`SCANNER_MODE_FULL`, PDF output), same integration as OdooDrop, behind the `DocumentScanner` interface. Result lands in the library (inbox) untouched, indexed by hash, queued for OCR.
- Desktop: no camera capture in v1. Import via file dialog, drag-and-drop onto the window, and "watch inbox" (drop files in the folder from any source). Stretch: SANE integration on Linux by shelling out to `scanimage`.
- Share intent on Android: receive PDFs/images from other apps into the inbox (images get converted to PDF).
- Import always dedups: identical hash already in the library -> skip with notice.

### OCR pipeline
- Hybrid strategy (port from OdooDrop's `OcrHelper`): extract embedded text per page with PDFBox; pages below a text threshold get rendered and OCR'd.
- Android engine: MLKit text recognition v2 (bundled model, offline), behind the `OcrEngine` interface.
- Desktop engine: prefer `ocrmypdf` if found on PATH (best quality, can embed an invisible text layer); fallback to Tess4J/tesseract for text extraction only.
- Results are written as OCR sidecars in `.npn/ocr/` (see document model) and cached in the local index. Devices adopt existing sidecars instead of re-OCRing, so each document is OCR'd once across the whole device set. v1.x improvement: embed the text layer **at import time, before the file is indexed** (desktop first via OCRmyPDF), so the document is still immutable afterwards and the text lives in the file itself.
- OCR runs as background queue (WorkManager on Android, coroutine worker pool on desktop), never blocks import.

### Tags and tag management
- Dedicated tag management screen: create, rename, merge, delete tags; per-tag document counts. Rename/merge/delete are single journal events (`tag-rename`/`tag-merge`), instant regardless of library size; no file rewriting.
- Tag CRUD with autocomplete from existing tags; tag chips in the library list; multi-select bulk tagging.
- Every tag change -> one journal event -> index update. Writes are cheap and immediate; no debouncing needed.
- Tag colors/icons: cosmetic metadata, stored as journal events too (`tag-style`), so they sync for free.

### Document properties (paperless-ngx parity)
- Document date: distinct from file mtime. Auto-extracted from OCR/embedded text (date-pattern heuristics, pick most plausible, user-confirmable), manually editable. Stored as `prop-set` journal events.
- Correspondent and document type: single-value properties like paperless-ngx, stored as journal events, managed like tags (autocomplete, merge/rename). v1 keeps these simple string properties; custom fields are post-v1.
- Document status lifecycle: inbox -> reviewed/filed (drives the review queue and the kanban default grouping). Journal event; kanban drag-and-drop is a single journal event, conflict-free.

### Search and browse
- Built-in FTS over the three axes: file/document names, file content (embedded text + OCR), and tags. Also matches correspondent and document type.
- Filters: tags (AND/OR/NOT), correspondent, document type, date ranges (document date and added date), folder, status, untagged/needs-review. Search results with content snippets and hit highlighting.
- Library views, all with cached first-page thumbnails:
  1. List (dense, metadata-forward)
  2. Grid (thumbnail-forward)
  3. Kanban: horizontally scrolling lanes of preview cards. Grouping dimension is configurable: status (inbox/review/filed, default), a chosen tag set, correspondent, or document type. Drag-and-drop between lanes updates the underlying property/tag.
- Saved views: named filter+sort+view-type combinations, pinnable to the home screen/sidebar (paperless-ngx saved views). Stored locally, export/import as JSON.
- Duplicate handling: import dedups by hash, so duplicates only appear through external file copies; a maintenance screen lists them and offers one-tap cleanup (keep canonical path, remove extras).
- Trash: soft delete moves files to `.npn/trash/` (syncs across devices, excluded from index) plus a `doc-trash` journal event carrying the original path for restore; name collisions in trash are avoided with hash-suffixed filenames. Restore support, configurable auto-purge (default 30 days).
- Document viewer: page rendering (Android `PdfRenderer`, desktop PDFBox), pinch/zoom, jump to search hit if cheap, otherwise defer.

### Screens (v1)
1. Onboarding: pick/create library folder, explain sync model (point syncthing/cloud client at it)
2. Library: search bar, filter panel (tags, correspondent, type, dates, status), sort, list/grid/kanban switcher, saved views sidebar/drawer, conflict banner
3. Document detail: preview, tags editor, properties (date, correspondent, type, status), metadata, rename/move/delete, export/share (baked) and bake-in-place
4. Scan/import: FAB -> MLKit (Android); import button + drag-drop (desktop)
5. Review queue: inbox-status and untagged documents (also reachable as a built-in saved view)
6. Tag management: tag list with counts, rename/merge/delete, colors; same pattern for correspondents and document types
7. Trash: restore/purge (plus duplicates maintenance)
8. Settings: library location, OCR language/engine, indexing behavior, trash retention, about

### Feature parity reference
Covered from paperless-ngx: tags, correspondents, document types, document date detection, inbox/review workflow, saved views, full-text search with filters, duplicate detection, bulk editing, trash.
Covered from Papra: simple tag management, trash/restore, deduplication.
Moved to post-v1: matching/auto-assignment (tagging rules); the future Gemma-based suggester plugs into the same pipeline.
Deliberately excluded (web/hosting or multi-user concerns): web UI, REST API, multi-user/permissions, email ingest, server-side consumption folders (the library folder itself covers this), audit trail, custom fields (post-v1 candidate), ASN/archive serial numbers (post-v1 candidate if physical archiving demand exists).

## 6. Phased delivery

### Phase 0: spikes (1-2 weeks)
Goal: kill the risky unknowns before committing to the design.
1. CMP skeleton building for Android + desktop from one repo, CI (GitHub Actions) producing debug APK + desktop distributable.
2. Room 3 on both targets; verify the FTS5 entity works on the JVM target with `BundledSQLiteDriver`. Decision point: Room-only vs Room + SQLDelight FTS table.
3. Journal prototype: event model (HLC, OR-set dots) + fold in commonMain, property-based tests for OR-set and HLC-LWW determinism (shuffled event orders converge); two simulated devices over a syncthing-synced folder, verify zero conflicts and correct convergence including offline divergence and compaction (segment merge preserves fold results).
4. Segment writes on Android SAF: verify create-and-write-once segment files via DocumentFile work reliably; measure rescan of a 1,000-file tree and initial full-hash indexing, timing.
5. XMP bake roundtrip (lower priority, not release-blocking): bake tags with pdfbox-android and Apache PDFBox into a copy, read back with exiftool to confirm interoperability.

Exit criteria: journal convergence proven under simulated multi-device sync, DB layer chosen, CI green.

### Phase 1: library core (3-4 weeks)
- `LibraryFileSystem` abstraction (SAF actual, java.nio actual + WatchService), including `.npn/` handling
- Indexer: rescan with fast-path/slow-path change detection, hash identity, import-time dedup, external-duplicate flagging, disappeared-file detection feeding the relink flow; initial full-hash pass as background job with progress
- Journal engine productionized from spike: segment writer, incremental fold with per-segment tracking, compaction (segment merge), deviceId fork detection
- Room schema: documents, tags, document_tags, properties, fts_content, thumbnails, scan/ocr queue, saved_views, folded_segments
- Library screen with thumbnails, list + grid views, sort, and plain-text search over filenames
- Settings + onboarding (folder selection both platforms)

### Phase 2: scan and OCR (2-3 weeks)
- MLKit document scanner integration (Android) behind `DocumentScanner` interface, share-intent import
- Desktop import: dialog + drag-drop, image-to-PDF conversion
- OCR queue with both engines, hybrid extract-or-OCR logic, sidecar write/adopt in `.npn/ocr/`
- FTS wired to OCR text; search results with snippets

### Phase 3: tags and metadata (2-3 weeks)
- Tag editor UI, autocomplete, bulk tagging, tag filters in library (all journal-backed)
- Tag management screen (rename/merge/delete as journal ops); correspondents and document types
- Document date extraction heuristics + manual property editing; status lifecycle (inbox/reviewed)
- Relink flow for externally modified files (heuristics: path/name, size + page-count fingerprint, baked `npn:documentId`)
- External conflict-file detection (pattern list) with keep/discard/import-as-new
- Review queue for inbox/untagged documents
- XMP bake: export/share with baked copy; manual bake-in-place (single + bulk) with relink event and sync-cost warning

### Phase 4: organization and views (2-3 weeks)
- Kanban view with preview cards, configurable grouping, drag-and-drop between lanes
- Saved views (create from current filters, pin, manage), export/import JSON
- Trash (soft delete to `.npn/trash/` + journal event with original path, restore, auto-purge) and duplicates maintenance screen
- Advanced search filters (correspondent, type, dates, status) + snippets/highlighting polish

### Phase 5: polish and release (2-3 weeks)
- Desktop packaging: deb/rpm + AppImage/Flatpak (Linux only in v1)
- Android: GitHub releases APK (signed, reproducible build), IzzyOnDroid submission; F-Droid main repo deferred until the FOSS flavor exists
- Performance pass with a large (5k+ docs) library; index paging
- Docs: README, sync setup guide (syncthing recipe incl. recommended Android client), CONTRIBUTING

### Phase 6+: post-v1 roadmap (ordered)
1. **Auto-tagging rules** (paperless-ngx matching / Papra tagging rules): conditions (filename/content contains/matches any-all, regex option) -> actions (assign tags, correspondent, document type). Run on import and after OCR; applied suggestions marked auto-assigned and revertible; dry-run preview; rules stored locally, export/import JSON. The future Gemma `TagSuggester` plugs into the same action pipeline
2. **FOSS Android flavor**: CameraX capture + edge detection scanner, Tesseract OCR, selected via existing `DocumentScanner`/`OcrEngine` interfaces; unlocks F-Droid main repo
3. **Windows/macOS packaging (v2)**: same JVM desktop target; add `.msi`/`.dmg` distributions, Tesseract bundling per platform, signing/notarization on macOS, CI matrix
4. **Embedded OCR text layer** applied at import time, before indexing, so documents stay immutable (desktop via OCRmyPDF first, Android via PDFBox-written text layer later); supersedes sidecars for new imports
5. **DocumentProvider/cloud mode**: Android SAF already gives this partially; add provider-specific quirks handling and a desktop "point at your Dropbox folder" preset
6. **Pears p2p sync spike**: bare-pack a Hyperdrive-based worker, embed via pear-mobile/BareKit on Android (Kotlin API) and a standalone Bare worker process on desktop, IPC over framed stream. Only promote to a feature if the spike shows acceptable complexity; syncthing mode remains the default
7. **Local AI tagging**: `TagSuggester` interface in core now, implementations later: MediaPipe LLM inference + Gemma (Android), llama.cpp JNI bindings or local Ollama detection (desktop). Suggestions always human-confirmed
8. **iOS**: CMP iOS target + VisionKit scanner (`VNDocumentCameraViewController`) via Kotlin/Native interop, Vision framework OCR
9. **Custom fields and ASN**: paperless-ngx-style typed custom fields and archive serial numbers as new journal event types (included in bake output), if demand shows up

## 7. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Journal fold bug produces divergent state across devices | Inconsistent libraries | Deterministic merge semantics (OR-set with dots, HLC LWW); property-based convergence tests in spike 0.3; index always rebuildable from folder |
| Journal compaction loses information | Wrong fold results | Compact only own segments; snapshot preserves dots, winning HLCs, and tombstones; compaction covered by convergence tests |
| Two installs share a deviceId (backup restore, clone) | Single-writer invariant broken | Startup check: newest own segment must be locally known, else fork a fresh deviceId |
| Concurrent bake-in-place on two devices | PDF conflict copy + competing relinks | Deterministic relink resolution (HLC + device-id); conflict-file flow handles the PDF copy; bake is user-invoked and rare |
| Misconfigured device clock skews HLC LWW | Wrong edit wins | Clamp absurd timestamps against local clock; device-id tiebreak keeps determinism |
| External edits change file hash | Metadata detaches | Relink flow (name/path heuristics, size + page-count fingerprint, baked `npn:documentId`); documents rarely edited externally in a scan archive |
| Bake-in-place corrupts a document | Data loss | Only user-invoked, rare; temp file + parse-verify + atomic rename; never write in place |
| pdfbox-android lacks xmpbox / XMP gaps | Baking broken on Android | Own XMP RDF/XML serializer in commonMain; PDFBox only moves raw metadata bytes; not release-blocking (desktop can bake) |
| Room 3 FTS5 entity broken on JVM target | No full-text search | SQLDelight for the FTS table only. Spike 0.2 |
| SAF too slow/limited on Android | Poor UX on big libraries | mtime fast-path rescans; write-once segments need no append; opt-in all-files access mode |
| Syncthing Android ecosystem churn (official app discontinued, fork ownership changes) | Users lose the sync layer | App is sync-agnostic; setup guide names currently maintained clients and is kept updated; any folder-sync tool works |
| Cloud clients skipping hidden dot-folders | `.npn/` silently not synced | Documented per-client in sync guide; syncthing (primary target) unaffected |
| External conflict files (user edits, other apps) | Duplicate/ghost docs | Pattern-list detection, exclusion, keep/discard/import-as-new |
| CMP desktop on Wayland (XWayland) | Cosmetic/DPI issues | Test on GNOME/KDE; track upstream JBR WLToolkit work |
| Solo-project scope creep | Never ships | Phases 0-5 are the release; rules and FOSS flavor explicitly post-v1 |

## 8. Testing strategy

- commonMain unit tests: indexer diff logic (incl. import dedup and external-duplicate flagging), journal fold (OR-set dots, HLC LWW, rename/merge ops, tombstones, relink resolution), XMP serializer roundtrips, search query building
- Property-based convergence tests: random event sets folded in shuffled orders and partial-sync interleavings must converge on identical state; segment compaction must preserve fold results; HLC comparison total order verified
- JVM-target tests double as fast integration tests for PDF read and bake against fixture PDFs (generated + real-world samples, including files produced by MLKit scanner)
- Android instrumented smoke test: scan-result handling, SAF filesystem actual, segment writes via DocumentFile, deviceId fork detection
- Manual test matrix per release: fresh library, 1k-file library, syncthing two-device sync with offline divergence, sidecar adoption across devices, external-edit relink, bake-in-place roundtrip (incl. concurrent-bake conflict)

## 9. Reuse from OdooDrop (secondary, but free)

- `OcrHelper` hybrid extract/render/OCR strategy -> core androidMain + desktopMain ports
- MLKit document scanner activity-result wiring -> composeApp androidMain
- Room migration discipline, WorkManager patterns, DataStore settings, manual DI style
- Not reused: Odoo client, billing, upload/sync repository (different model entirely)
