# Cloud sync qualification

This document is the repeatable proof that cloud sync (`core.sync`, ARCHITECTURE.md 5.6) converges
for the providers below (a representative part of the app's catalog), and the record of what a
failure looks like. It has two halves:

- an **automated suite** that drives the real engine, journal and index over a fake remote, in
  `core/src/desktopTest/kotlin/dev/npn/core/sync/SyncScenarioTest.kt`;
- **manual provider runs** that exercise rclone, OAuth, the provider's own API and the two real
  apps, recorded from this document's templates.

The automated suite is what CI runs; a manual run is what qualifies a provider for a release. They
are complementary: the fake remote cannot rate-limit, expire a token, fold case on the server or
reproduce a real process kill (the suite cancels the pass where the kill would land and reopens the
library, or cuts an upload to leave a half-written object), and a real provider cannot replay a two-device interleaving on demand.

No credential, token, account name, e-mail address or remote URL with an embedded password belongs
in this repository. Credentials live in the app-private rclone config that the setup wizard writes;
qualification records live beside this document as gitignored local files (`docs/sync-runs/`), and
only the redacted result table is pasted into the release checklist or an issue.

## Running the automated suite

```
# The whole scenario suite (under a minute on a laptop):
mise sync-scenarios

# The same task, directly, when mise is not in use:
./gradlew :core:desktopTest --tests 'dev.npn.core.sync.SyncScenarioTest'

# One scenario:
./gradlew :core:desktopTest --tests 'dev.npn.core.sync.SyncScenarioTest.aRestoredDeviceRebuildsTheLiveLibraryAndKeepsTheTrashAndTombstones'

# The engine suites the scenarios build on, for a change to the planner or a transfer:
./gradlew :core:desktopTest --tests 'dev.npn.core.sync.*'
```

The suite uses `SyncEngineTestBase`, `SyncTestSupport`'s `DirectoryRemoteStore`/`DirectoryTransport`
and `SyncCluster`: real `LibrarySessionImpl` sessions, real journal, real Room index and device
databases, one library folder per device, and one directory standing in for the remote. No network
call, no rclone process, no credentials. A scenario that converges (`SyncCluster.converge`) also
proves what the settings screen would say: once a round changes nothing, no replica's last pass
owes an upload or a download, and each replica's `SyncHealth.healthy` holds (unless the scenario
expects conflicts).

| Test | Scenario |
| --- | --- |
| `offlineEditsOnBothSidesConverge` | S1 offline edits on both sides |
| `simultaneousMetadataEditsKeepTheLaterWriteAndBothTags` | S2 simultaneous metadata edits |
| `aRenameTravelsWhileTheOtherDeviceEditsMetadata` | S3 rename |
| `aRenameSwapKeepsBothDocumentsButTheirPathsStayApart` | S4 rename-swap (known limit) |
| `aTrashOnOneDeviceWinsOverAMetadataEditOnTheOtherAndRestoreBringsItBack` | S5 trash/restore |
| `aDeleteForGoodRemovesEveryCopyAndSurvivesAReplicaThatWasOffline` | S6 delete for good |
| `aPassKilledAfterAnUploadLandsAdoptsTheRemoteCopyWithoutADuplicate` | S7 process death after an upload |
| `aTruncatedUploadIsKeptAsAForeignCopyAndTheDocumentSurvives` | S7 upload cut short (known limit) |
| `SyncEngineTest.anInterruptedDownloadLeavesNothingInTheLibraryAndIsRetried` | S7 download cut short (the engine suite) |
| `aMultiMegabyteDocumentTravelsIntact` | S8 large files (3 MiB) |
| `aThirdReplicaJoiningGetsEverythingWithoutPushingAnythingBack` | S9 third device joining |
| `aRestoredDeviceRebuildsTheLiveLibraryAndKeepsTheTrashAndTombstones` | S10 restore from an empty device |

What the suite deliberately cannot prove, and a manual run must: rclone's own behaviour, the
provider's identity/hash and case-folding rules, OAuth refresh and expiry, rate limits and their
retries, upload sessions and their server-side behaviour, the provider's own trash/versioning,
a real process kill (the suite cancels the pass at the same point and reopens the session instead), an interrupted *journal-object*
upload (mark that part of S7 manual; PDF uploads and downloads are automated), a real filesystem's
locking and discard semantics, and Android's scoped storage.

## Recording a manual run

Use two devices wherever possible (desktop + desktop, or desktop + phone); one device cannot show
what a second replica sees. For every run:

1. Create a dedicated account or app password and a test folder named
   `npn-qualification/<provider>-<yyyy-mm-dd>-<run>`. Never use a personal drive root.
2. Write down the provider, backend, rclone version (`rclone version` from the app's bundled binary,
   or the release's pinned version), app version (`BuildInfo`), devices and OS versions, and the
   scenario rows you ran.
3. Capture, per scenario, the evidence S1–S10 asks for. Redact account names and links before
   pasting anything into an issue.
4. Save the record as `docs/sync-runs/<yyyy-mm-dd>-<provider>-<run>.md` using the template below.
   That directory is gitignored on purpose; attach the redacted record to the release notes or the
   issue.

```
# <provider> sync qualification — <date>

- App: <version> (<BuildInfo>), <commit>
- rclone: <version>
- Devices: <device A> (<OS>), <device B> (<OS>)
- Remote: <provider backend>, test folder <name>, case folding <yes/no>
- Result: <passed / failed / passed with limitations>

| Scenario | Result | Evidence | Notes |
| --- | --- | --- | --- |
| S1 offline edits | | | |
| ... | | | |

## Findings
<one line each, with the evidence file name; link the issue>
```

### Evidence to attach, by hand

- **Sync report**: Settings > Cloud sync, the last run's summary (documents and objects transferred,
  conflicts, errors). A screenshot is enough; do not paste the provider's signed URLs.
- **Remote state**: the provider's web UI or `rclone lsf -R <remote>:<folder>` listing, showing the
  library paths, `.npn/journal`, `.npn/trash`, and that no unexpected copy exists.
- **Local state**: the library folder listing and the SHA-256 of each document file (`sha256sum`),
  plus the document list and metadata in the app on both devices.
- **Identity**: for a document both devices hold, the same SHA-256 on both; for a provider with a
  weak hash (MD5, SHA-1, quickXor), the sync report's download count on a new device proves the
  engine fell back to a download instead of trusting it.
- **Failure detail**: the pass's error text and `NpnLog` entries (the session log), and for a crash
  the OS log. A conflict is not a failure: `TARGET_OCCUPIED`, `UPLOADED_AS_COPY`,
  `INSTALLED_AS_COPY`, `UNIDENTIFIED`, `REMOTE_CHANGED`, `LOCAL_CHANGED` and `KEPT_ON_THIS_DEVICE`
  are the engine's documented answers (ARCHITECTURE.md 5.6) and the report names both paths.

## Providers

| Provider | rclone backend | Sign-in | Identity the engine can use | Case | Delete for good |
| --- | --- | --- | --- | --- | --- |
| Dropbox | `dropbox` | OAuth2 | Dropbox content hash (SHA-256 over 4 MiB blocks) only for a library file of the same size; otherwise download | Folds case | rclone delete removes it; Dropbox's own deleted-files area keeps it for a while |
| Google Drive | `drive` | OAuth2 | none usable (MD5 only) → download | Name lookups fold case; a folder may hold duplicate names | rclone delete moves it to Drive's trash by default (`--drive-use-trash`) |
| OneDrive | `onedrive` | OAuth2 | none usable (quickXorHash) → download | Folds case | Graph DELETE moves it to the recycle bin |
| WebDAV / Nextcloud | `webdav` | URL + user + app password | none on the wire the engine trusts → download | Server-dependent; Nextcloud often folds case, so treat it as case-insensitive | WebDAV DELETE; Nextcloud may keep it in its own trash/versions |
| S3 | `s3` | Access key + secret + region | ETag only for single-part uploads (MD5), never trusted | Case-sensitive | Permanent, unless bucket versioning keeps delete markers/versions |
| Backblaze B2 | `b2` | Key ID + application key | SHA-1 only, never trusted → download | Case-sensitive | Hides the file; old versions remain until the bucket lifecycle rule |
| SFTP | `sftp` | Host + user + key or password | none the engine trusts by default (rclone may report MD5/SHA-1 when the server has a checksum command) → download | Server filesystem | Permanent |

Provider deltas that matter to every scenario:

- **Dropbox**: measured limits are metadata 16 down/32 up and PDFs 8 each way (`TransferLimits`); past
  that it throttles, and a refused write (`too_many_write_operations`) is retried twice. An upload
  over roughly 150 MiB uses an upload session, which does not leave a visible partial object.
- **Google Drive**: every document is downloaded once to identify it on a new device because MD5 is
  not evidence. A folder can contain two files with one name; the engine plans by path and reports
  an occupied target rather than replacing anything. `--drive-use-trash` (on by default) means a
  delete for good still leaves the bytes in Drive's trash for the user to purge there.
- **OneDrive**: same download-to-identify cost (quickXorHash is ignored), and a delete for good goes
  to the site recycle bin, not away. Names differing only in case collide.
- **SFTP**: rclone can report MD5 or SHA-1 when the server offers a checksum command, but the engine
  trusts neither, so every document is downloaded once to identify it on a new device. A case-folding
  mount behaves like Dropbox for paths; a full disk fails uploads per file.
- **Nextcloud/WebDAV**: no hash and no atomic multi-file commit. Its mtime may have one-second
  resolution; a same-size change that keeps the same reported mtime leaves the manifest's size/mtime
  pair unchanged, so it is trusted rather than re-identified and the change is missed until the
  mtime moves. Its trashbin and file versions can hold a copy after the app's delete.
- **S3**: no native mtime; a server-side copy resets the reported time, so the engine re-identifies
  the file after it or a foreign tool touches it. A multipart ETag is not a content hash and is never
  used as one.
- **B2**: every document is downloaded once to identify; delete hides rather than erases, so a
  "delete for good" is only permanent once the bucket's lifecycle removes old versions. Large files
  use B2's large-file upload.
- **SFTP**: no provider trash and no rate limit, but the server's filesystem is the source of truth:
  a case-folding mount behaves like Dropbox for paths, and a full disk fails uploads per file. rclone's
  SFTP hashes are MD5/SHA-1 unless configured otherwise, and the engine trusts neither, so a new
  device downloads documents to identify them.

## Scenario catalogue

Every cell of the matrix below refers to one of these. "Expected" is what the product promises; a
deviation is a failure unless the cell says otherwise.

### S1 — Offline edits on both sides

- **Setup**: two devices, both synced to quiescence, then network off on both (or automatic sync
  off and no manual pass).
- **Steps**: on A, import a new PDF and add a tag to an existing document. On B, import a different
  PDF and add another tag to the same existing document. Reconnect A, sync, then B, sync, then A
  again.
- **Expected**: each device holds all three documents at the same names, the existing document
  carries both tags, and the next pass on each device changes nothing.
- **A failure means**: a lost import, a lost tag, a duplicate document, or a pass that keeps
  transferring after convergence — stop and keep both devices' folders and logs.
- **Evidence**: both document lists, both tag sets, the remote PDF listing, and two consecutive sync
  reports showing zero transfers on the last pass.

### S2 — Simultaneous metadata edits

- **Setup**: as S1; both devices show the same document.
- **Steps**: with neither device syncing, set the title on A and a different title on B; add a tag
  on each; then sync A, B, A.
- **Expected**: the title with the later HLC on both devices (system clocks decide in practice; a
  tie is broken by device id), both tags present (tags are an OR-set), and no conflict or error.
- **A failure means**: different titles on the two devices after convergence (the fold disagrees),
  a lost tag, or an edit that silently overwrites the other without the HLC rule.
- **Evidence**: the title and tags shown on both devices, and the document's field values in each
  device's index (or a screenshot of the detail screen).

### S3 — Rename

- **Setup**: two synced devices holding one document.
- **Steps**: rename the document on A while B is offline; sync A, then B, then A. Repeat with a move
  into a subfolder if the storage supports relocation.
- **Expected**: the remote has only the new name, B's file moves to the new name, both rows show the
  new path, and the next passes are quiet. A rename made on B while A is offline behaves the same
  way in reverse.
- **A failure means**: the old name survives on the remote, a replica ends with two copies, or the
  document is missing on one device.
- **Evidence**: both library listings, the remote listing, both rows' paths, and the sync report
  (one moved remote on the renaming device, zero downloads on the other).

### S4 — Rename-swap (known limit)

- **Setup**: two synced devices holding two documents, `a.pdf` and `b.pdf`.
- **Steps**: while apart, swap their names on each device (through a temporary name, because a
  rename onto an occupied name is refused). Sync both.
- **Expected, per ARCHITECTURE.md 5.6**: both documents keep their bytes and their swapped library
  names; the remote keeps the names it had; each pass reports `TARGET_OCCUPIED` for the move it
  cannot make. This is a documented limit, not a failure: renames settle one at a time and wait
  while their name is taken.
- **A failure means**: either document is lost or overwritten, or a replica shows one document
  twice. Swapped names on the remote are expected until the user renames one aside.
- **Evidence**: both listings, both hashes per name, the conflict rows in the sync report, and the
  remote listing showing the names unchanged.

### S5 — Trash and restore

- **Setup**: two synced devices holding one document, with metadata worth keeping.
- **Steps**: on A, trash the document and sync. On B (offline meanwhile), edit a tag. Sync B, verify
  it moved its file into its own trash, then restore on B, sync B, sync A.
- **Expected**: A's trash reaches B as a trash (not a delete); B's offline tag survives into the
  restored document; after restore both devices show the file live at its original path with that
  tag; the remote holds the live file and no live stale copy. The mirrored trash normally reuses the
  trashing device's write-once entry; a replica that parks before it knows the trash time can add its
  own (issue #39), which the retention purge leaves while the document is live again.
- **A failure means**: the document comes back on a device that had not restored it, the restore
  downloads bytes that were already in the trash, or a tag edit is lost.
- **Evidence**: each device's trash/restore state, the live file's SHA-256, the tag, and the remote
  `.npn/trash` listing.

### S6 — Delete for good

- **Setup**: as S5, with a second document left in the trash.
- **Steps**: trash a document and delete it permanently on A; while B is offline, edit its metadata.
  Sync A, then B, then A.
- **Expected**: no live or trashed copy on either device or the remote; B's row is deleted and its
  file removed; the tombstone survives so neither a later rescan nor a new device brings it back.
  A provider with its own trash may still hold the bytes (see its row above); that copy is outside
  the library and cannot be listed by the app.
- **A failure means**: the file reappears, a device re-uploads it, or the remote still lists it.
- **Evidence**: both listings, both rows' deleted flag, the remote listing, and the provider's trash
  or version browser.

### S7 — Process death mid-transfer

- **Setup**: a large enough document that the transfer takes a moment; automatic sync off.
- **Steps**: start a pass and kill the app process (desktop: SIGKILL; Android: force stop) after the
  remote's activity page shows the upload began, or after it completed but before the app shows the
  pass finished. Reopen the app and sync. Repeat with a download interrupted by killing the app or
  cutting the network, and with a metadata (journal) upload interrupted.
- **Expected**: no half-written document at a live document's path; the next pass either adopts the
  completed remote copy (by identifying it) or uploads it again; a partial PDF may be kept as a
  separate copy document (the known limit below); the library's own document is never lost or
  overwritten, and exactly one live copy of each real document converges.
- **A failure means**: a corrupt document, a duplicate live copy of one document, or a pass that
  blocks on the partial object forever.
- **Known limit**: a backend that writes in place can leave a truncated PDF at the final remote name.
  The engine cannot distinguish those bytes from another tool's file, so it keeps them as a copy
  document and uploads the real one beside it
  (`aTruncatedUploadIsKeptAsAForeignCopyAndTheDocumentSurvives`); the
  library's document is never lost, but the user must delete the stray copy. Staged-upload providers
  (Dropbox upload sessions, Drive resumable uploads, S3 multipart, B2 large files) do not leave one.
  Tracked as issue #38.
- **Evidence**: the library listing after restart, the remote listing before and after the retry,
  the document's SHA-256, and the retry's sync report (plus the first report if the pass returned one).

### S8 — Large files

- **Setup**: two synced devices, free space on both and on the provider; a PDF near the provider's
  single-upload limit (for example 150 MiB for Dropbox's session path, above rclone's 200 MiB S3 multipart
  switch for S3, up to its 5 TiB object ceiling).
- **Steps**: import and sync; then interrupt the first upload (network off) and sync again; then
  verify the download on the second device, including memory use of the app.
- **Expected**: the file transfers whole (the engine streams through staging, never reads a PDF into
  memory), the second device's SHA-256 matches, a retry after an interruption succeeds, and neither
  device shows a truncated document.
- **A failure means**: an out-of-memory crash, a truncated file that verifies because no hash check
  ran, or a retry that never completes.
- **Evidence**: both SHA-256 values, both file sizes, the app's memory graph during the transfer,
  the provider's activity/version page, and the second pass's report.

### S9 — A third device joining

- **Setup**: two synced devices with history: a renamed document, a tagged document, one in the
  trash and one deleted for good.
- **Steps**: install the app on a third device (or open a fresh library), connect the same remote
  folder, run the first pass and wait for the index; then sync all three again.
- **Expected**: the new device shows the live documents at their journaled names, the tags and field
  values, the trashed document in its trash, and nothing of the deleted one. It uploads only its own
  settings/metadata objects, never a duplicate document, and the next passes are quiet.
- **A failure means**: a duplicate live copy, a deleted document resurrected, missing metadata, or a
  refusal (`OTHER_LIBRARY`) when the folder is the library's.
- **Evidence**: the third device's list, trash and detail screens, its sync report (downloads only),
  and the remote listing before and after (no new document names).

### S10 — Restore from an empty device ("your laptop died")

This is the user-facing walkthrough; the same steps qualify a new phone.

1. Install the app on the new device and do not open an old copy of the library folder. A copy of
   the old folder is not a restore: it can be opened later and reconnected, and two copies of one
   library must not be connected to different remotes.
2. In the app, choose to start a library in the cloud (or create an empty local library and connect
   it in Settings > Cloud sync). Sign in to the same provider account and pick the same folder the
   old device used. The setup wizard refuses a remote's top level and a folder of unrelated files.
3. Run the first pass and let the index finish. Expected: every live document comes back at its
   journaled name with its metadata, tags and OCR/journal-derived state; documents in the trash come
   back into this device's trash; documents deleted for good stay gone; settings, saved views and
   rules come back (they are journal data; the fake suite does not assert them, so check them by
   hand here).
4. Compare a sample of documents by SHA-256 with the remote, and check the trash count and the
   document count against the old device's last known state.
5. Expect and accept these conflicts after a restore: a document the old device renamed but whose
   remote move never completed appears under the old name with `TARGET_OCCUPIED` on a later pass; a
   foreign file at a library name is installed as a copy (`INSTALLED_AS_COPY`). These conflicts do
   not delete or replace anything.
   If the remote's only copy is truncated, the restore is incomplete: downloading and hashing it
   cannot recover the original bytes. Recover the document from provider versions or another backup,
   and verify its hash before treating the restore as complete.
6. If the provider's trash or versions still hold deleted documents, purge them there only if a
   permanent delete is intended; the app cannot see them.

- **A failure means**: missing live documents, a resurrected deleted document, a credential or
  folder error that refuses the library, or a pass that loops without converging.
- **Evidence**: the new device's library count/list, the trash list, the sync report, the remote
  listing, and the old device's last report if it is still available.

## Provider × scenario matrix

Each cell is "what this provider must show". The setup and steps are the scenario's, with only the
provider-specific delta written here; evidence is the scenario's plus the provider's own activity,
trash/version or listing page. A cell marked **manual** cannot be automated with the fake remote.

### Dropbox

| Scenario | Setup & steps (delta) | Expected | A failure means | Evidence |
| --- | --- | --- | --- | --- |
| S1 | Full Dropbox or app folder; two devices | Standard; content hash identifies library files without a download | Lost import or tag | Both lists, remote listing, report |
| S2 | — | Standard; no rate-limit errors | Different titles, lost tag | Detail screens, report |
| S3 | — | Move is server-side; case-only rename lands on the new case | Old name survives, duplicate | Remote listing, report |
| S4 | — | Known limit: swapped names stay apart, `TARGET_OCCUPIED` | Lost or duplicated document | Both listings, conflicts |
| S5 | — | Trash entry per device until purge; restore moves it back | Resurrection before restore | Trash listings, report |
| S6 | Check Dropbox's deleted files | Remote no longer lists it; Dropbox may keep it 30 days | File still listed live | Remote listing + deleted files |
| S7 | **manual**: kill the app during an upload session | Adopted or retried; no partial document | Corrupt/duplicate document | Remote activity, library hashes |
| S8 | ~150 MiB file (session path) | Whole, streamed, SHA-256 matches | OOM, truncation | Sizes, hashes, memory |
| S9 | Third device, same account | Downloads only, metadata complete | Duplicate or refusal | Third report, remote listing |
| S10 | New device, same folder | Live library + trash back, deletes stay gone | Lost/resurrected document | New list, trash, report |

### Google Drive

| Scenario | Setup & steps (delta) | Expected | A failure means | Evidence |
| --- | --- | --- | --- | --- |
| S1 | My Drive test folder | Standard; every document downloaded once to identify | Lost import or tag | Both lists, report |
| S2 | — | Standard | Different titles, lost tag | Detail screens |
| S3 | — | Standard; duplicate names in a folder are never created by the engine | Duplicate name left behind | Drive listing, report |
| S4 | — | Known limit, `TARGET_OCCUPIED` | Loss | Both listings, conflicts |
| S5 | — | Standard | Resurrection | Trash views, report |
| S6 | **manual**: `--drive-use-trash` | App copy gone; bytes in Drive trash until purged there | Live copy still listed | Drive trash browser |
| S7 | **manual**: kill during a resumable upload | Adopted or retried; no partial document | Corrupt/duplicate | Drive activity, hashes |
| S8 | 1 GiB file, **manual** (the fake suite proves 3 MiB integrity) | Whole, streamed | OOM/truncation | Sizes, hashes, memory |
| S9 | Third device | Downloads only | Duplicate/refusal | Report, listing |
| S10 | New device | Standard restore | Lost document | New list, report |

### OneDrive

| Scenario | Setup & steps (delta) | Expected | A failure means | Evidence |
| --- | --- | --- | --- | --- |
| S1 | OneDrive test folder | Standard; quickXorHash ignored, downloads to identify | Lost import or tag | Lists, report |
| S2 | — | Standard | Title/tag divergence | Detail screens |
| S3 | — | Standard; case-insensitive names | Case-duplicate, old name | Listing, report |
| S4 | — | Known limit, `TARGET_OCCUPIED` | Loss | Listings, conflicts |
| S5 | — | Standard | Resurrection | Recycle bin + app trash |
| S6 | **manual**: check the recycle bin | App copy gone; Graph DELETE leaves it in the bin | Live copy remains | OneDrive recycle bin |
| S7 | **manual**: kill during an upload session | Adopted or retried | Corrupt/duplicate | Activity, hashes |
| S8 | 1 GiB file, **manual** | Whole, streamed | OOM/truncation | Sizes, hashes |
| S9 | Third device | Downloads only | Duplicate/refusal | Report, listing |
| S10 | New device | Standard restore | Lost document | New list, report |

### WebDAV / Nextcloud

| Scenario | Setup & steps (delta) | Expected | A failure means | Evidence |
| --- | --- | --- | --- | --- |
| S1 | App password; test folder on the server | Standard; downloads to identify | Lost import/tag | Lists, report |
| S2 | — | Standard | Title/tag divergence | Detail screens |
| S3 | — | Standard; server-side move, no case-only surprises on a case-folding mount | Old name/duplicate | Server listing |
| S4 | — | Known limit, `TARGET_OCCUPIED` | Loss | Listings, conflicts |
| S5 | — | Standard; Nextcloud's own trash may also hold the trashed copy | Resurrection | Server trash + app trash |
| S6 | **manual**: Nextcloud trash/versions | App copy gone; server trash/versions may remain | Live copy listed | Nextcloud trash/versions |
| S7 | **manual**: kill the app mid-upload | Adopted or retried; no partial document | Corrupt/duplicate | Server listing, hashes |
| S8 | 1 GiB file | Whole, streamed | OOM/truncation | Sizes, hashes, server quota |
| S9 | Third device | Downloads only | Duplicate/refusal | Report, server listing |
| S10 | New device | Standard restore | Lost document | New list, report |

### Amazon S3

| Scenario | Setup & steps (delta) | Expected | A failure means | Evidence |
| --- | --- | --- | --- | --- |
| S1 | Bucket + prefix, region, keys | Standard; ETag never trusted | Lost import/tag | Lists, bucket listing |
| S2 | — | Standard | Title/tag divergence | Detail screens |
| S3 | — | Standard; copy+delete under the hood, mtime resets | Old key/duplicate | Key listing, report |
| S4 | — | Known limit, `TARGET_OCCUPIED` | Loss | Listings, conflicts |
| S5 | — | Standard | Resurrection | Trash prefix listing |
| S6 | **manual**: versioning/lifecycle | Live copy gone; versions/delete markers per bucket policy | Live key remains | Bucket versions |
| S7 | **manual**: kill during a multipart upload | Adopted or retried; no partial key | Corrupt/duplicate | Bucket listing, hashes |
| S8 | Above 200 MiB (multipart), up to 5 TiB | Whole, streamed | OOM/truncation/failed multipart | Sizes, hashes, bucket parts |
| S9 | Third device | Downloads only | Duplicate/refusal | Report, listing |
| S10 | New device | Standard restore | Lost document | New list, report |

### Backblaze B2

| Scenario | Setup & steps (delta) | Expected | A failure means | Evidence |
| --- | --- | --- | --- | --- |
| S1 | Bucket + prefix, key ID/application key | Standard; SHA-1 ignored, downloads to identify | Lost import/tag | Lists, bucket listing |
| S2 | — | Standard | Title/tag divergence | Detail screens |
| S3 | — | Standard; server-side copy with mtime in file info | Old name/duplicate | Bucket listing |
| S4 | — | Known limit, `TARGET_OCCUPIED` | Loss | Listings, conflicts |
| S5 | — | Standard | Resurrection | Trash prefix |
| S6 | **manual**: lifecycle rule | Live copy hidden; old versions until lifecycle | File still visible | Bucket file versions |
| S7 | **manual**: kill during a large-file upload | Adopted or retried; no partial document | Corrupt/duplicate | Bucket listing, hashes |
| S8 | 1 GiB+ large file, **manual** | Whole, streamed | OOM/truncation | Sizes, hashes, unfinished large files |
| S9 | Third device | Downloads only | Duplicate/refusal | Report, listing |
| S10 | New device | Standard restore | Lost document | New list, report |

### SFTP

| Scenario | Setup & steps (delta) | Expected | A failure means | Evidence |
| --- | --- | --- | --- | --- |
| S1 | Host, user, key/password; test folder | Standard; documents downloaded once to identify (MD5/SHA-1 are not evidence) | Lost import/tag | Lists, `sha256sum` on the server |
| S2 | — | Standard | Title/tag divergence | Detail screens |
| S3 | — | Standard; rename, or copy+delete across filesystems | Old name/duplicate | Server listing |
| S4 | — | Known limit, `TARGET_OCCUPIED` | Loss | Listings, conflicts |
| S5 | — | Standard | Resurrection | App trash, server listing |
| S6 | — | Permanent delete; no provider trash | File still present | Server listing |
| S7 | **manual**: kill the app mid-upload; check for a temp name | Adopted or retried; no document from a partial file | Corrupt/duplicate | Server listing before/after |
| S8 | 1 GiB file | Whole, streamed | OOM/truncation/full disk | Sizes, hashes, `df` |
| S9 | Third device | Downloads only | Duplicate/refusal | Report, listing |
| S10 | New device | Standard restore | Lost document | New list, report |

## Known limitations and findings

These are the limits the qualification run pins down. None is fixed here; each is a documented
answer or an issue to track.

- **Rename-swap stays apart** (ARCHITECTURE.md 5.6). Two documents that swap names keep their bytes
  and their swapped library names, while every pass reports `TARGET_OCCUPIED` for the moves it
  cannot make; the remote keeps the old names. `aRenameSwapKeepsBothDocumentsButTheirPathsStayApart`
  pins this. Resolution for the user today: rename one document aside and back.
- **A truncated upload on a backend that writes in place is adopted as a copy document** (S7). The
  engine sees a file at the remote name whose bytes are no document it knows, identifies it by
  download, and installs it as a new document; the library's real document then goes up as
  `name (2).pdf`. Nothing is lost, but the stray copy is the user's to delete. `--inplace`-style
  backends, or a provider whose interrupted upload leaves a visible partial object, are the only
  way to reach this; staged uploads do not.
- **A mirrored trash can add its own trash entry** (S5, S9). A replica that parks a file it only
  just learned was trashed may not have the trash time in its row yet, so it names the entry with its
  own clock and uploads it beside the trashing device's entry; the remote then holds one entry per
  such replica (the S9 run shows two, with different episodes). A replica that already carries
  the trash time reuses the same write-once name and adds nothing. Extra entries serve the retention
  purge, which removes an entry only once every document its name can stand for has been out of the
  library longer than the retention. While a restored document is live again, that condition is not
  met, so a leftover entry can stay on the remote until the document is trashed again or deleted;
  it never becomes a second document. The app shows the document once. Tracked as issue #39.
- **A deleted document has no row on a device that never held it.** The tombstone is in the journal
  and nothing installs it (unlike a trashed document, whose row the fold can synthesize); the fresh
  device simply does not list it. Nothing to fix, but do not expect a deleted item in a new
  device's trash.
- **Manual-only coverage**: provider rate limits, OAuth expiry/revocation, server-side case folding,
  upload-session resume, provider trash/versioning, real process kills and Android scoped storage
  are not in the fake remote. They are the **manual** cells above.
