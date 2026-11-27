# Library simulation

The library simulation (`LibrarySimulationTest1` to `LibrarySimulationTest8` in
`core/src/desktopTest/kotlin/dev/npn/core/sim/`, one test class per shard of the seeds) runs two to four replicas
of one library, each a real session (the same `openSession` and `start()` the apps use) over its own
in-memory folder and databases, with a file sync tool between the folders, all on one virtual clock,
driven by seeded generators of operations. After each step it checks the library's invariants. At
rest, it compares each replica's index with one rebuilt from that replica's files alone, and after a
full sync it checks that every replica shows the same library. A run is reproducible from its seed.

The bugs the session, journal, index and lifecycle code have had were interleavings: a rescan racing
a trash, a file replaced between a stat and a hash, a journal object arriving mid-operation, a settle
timer firing at the wrong moment. Sharing a library through a sync tool adds more of them: another
device's journal object, file move or conflict copy landing at any moment, in any order. The
simulation explores those schedules on purpose instead of pinning one per example test.

## Running it

```
# What CI runs (in :core:desktopTest): seeds 1..10, 250 steps each, about 35 s on a laptop.
./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest*'

# A long run: seeds 1..300, 400 steps each (or start elsewhere with -Dnpn.sim.firstSeed=), every
# failing seed reported rather than the first three of each shard.
./gradlew --max-workers=8 :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest*' -Dnpn.sim.seeds=300 -Dnpn.sim.steps=400 -Dnpn.sim.maxReported=300

# Replay one seed (a failure prints this line with its seed and step count).
./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest*' -Dnpn.sim.seed=1234 -Dnpn.sim.steps=400
```

The seeds are dealt round-robin over eight test classes, `LibrarySimulationTest1` to
`LibrarySimulationTest8`, and `core/build.gradle.kts` lets `:core:desktopTest` run up to eight test
JVMs at once (half the processors, within `--max-workers`), so a long run takes about an eighth of
the time on a machine with the cores. Within a JVM the seeds run one after the other: a seed is one
single-threaded run, and seeds sharing a JVM slow each other down far more than they share the
machine (the JVM's own shared structures, from the coroutine library's debug ids to the stack walks
Room does on every connection it hands out). A replay of one seed is a run of that one seed, so it
always runs in `LibrarySimulationTest1`; the other classes report that no seed fell to them and
pass.

`core/build.gradle.kts` forwards `npn.sim.seed`, `npn.sim.seeds`, `npn.sim.firstSeed`,
`npn.sim.steps`, `npn.sim.traceDir` and `npn.sim.maxReported` (per shard) to the test JVMs, given as
`-D` or `-P`. A replay needs only the seed and the step count, which decide everything else; a
failure's `Replay:` line names both. A changed value reruns the test task
instead of reusing its cached result. With `-Dnpn.sim.traceDir=<dir>`, every seed writes its whole
trace to `<dir>/seed-<n>.txt`, whether it fails or not. Each shard prints its seeds' real time, and
any step that took over a second.

A seed decides everything the simulation chooses: the replicas and their storage, the sync tool's
behaviour, every operation and its arguments, the latency of every filesystem call (which decides how
concurrent work interleaves), which watch events are lost, and every name core draws:
`SessionDeps.ids` is the session's source of journal actor ids, staging, import and recovery file
names, device ids and rule ids, and the simulation seeds it (`SimIds`). So a replay is the same run:
the same steps at the same virtual times, line for line in the trace.
`SyncSimulationHarnessTest.aSeedReplaysTheSameVirtualTimeline` checks that.

A failing run stops at its first broken invariant. Its report has the seed, the configuration (each
replica's storage profile, the watcher's losses, the sync tool), the faults still armed,
the last steps of the trace, the end of the session log (each entry names its replica), the failing
replica's files with their hashes and its index's document rows, the history of a path whose file is
not its document's, the journal events behind a document the rebuild shows otherwise, and the sync
tool's state (conflict copies, deliveries under way). A seed that hits a failure listed
in `KnownIssues.knownFailures` (a finding already written up) stops
there. Such a seed is counted and printed instead of failing the test, so a long run goes on to find
new bugs. A replay of that one seed still fails.

`SimulationHarnessTest` and `SyncSimulationHarnessTest` check that the harness itself can fail: the
rebuild opening other files, a rewritten journal object, a logged programming error, two versions of
a write-once name meeting in the sync tool, a replica deleting a journal object, a damaged journal
object, and the app deleting a document's last copy (alone, after or before another process took
the same bytes, in its trash for a document it never trashed, or while another replica holds the
bytes at the same path) each fail the run, while the app deleting a copy another replica holds at
another path, or a bake's temporary file, does not. Storage with no
publication primitive refuses the library. They also check that relinked bytes count as held only
with the relink chain's final target in the folder, that a replica joining late with an empty
folder ends up showing what the others show, and that a seed replays the same virtual timeline.

## Long runs

`.github/workflows/simulation.yml` runs the simulation long, on demand only: nothing schedules it
and no push or release waits for it. Start it by hand (`gh workflow run simulation.yml`, with
`-f seeds=`, `-f steps=` and `-f first_seed=` to change the defaults) after a change to the session,
index, journal, file system or sync code. With `first_seed` empty it takes the day's block of seeds
from 1000 on, keyed on the date, so it never repeats CI's seeds 1..10 and a run on a later day
covers fresh ones. `.github/scripts/simulation-seeds.sh` picks the seeds.
A seed of 400 steps costs about 9.6 s on a laptop core, and the eight shards run on eight cores at
that speed each (400 seeds took 9 minutes). A two-core hosted runner gets one test JVM, so at an
assumed 1.5 times the laptop's cost per seed, about 15 s, the default 60 seeds of 400 steps take
about fifteen minutes of simulation, under a 30-minute step timeout and a 40-minute job timeout.
Measured, a hosted runner took 18 to 22 minutes for 100 seeds of 400 steps, so the seed script
refuses a run of more than 32000 seed-steps (80 seeds of 400) before building anything, rather than
let the timeout cut it off with no result; a longer run is several runs with their own
`first_seed`. A
long local run (AGENTS.md, "Commands") is faster on a machine with more cores. The workflow's `--tests`
filter must name the shards, `dev.npn.core.sim.LibrarySimulationTest*`: the exact class name
matches nothing since the seeds were dealt over eight classes, and Gradle fails a filter that
matches no test. When a seed fails, the run uploads every seed's trace and the test report as an artifact
kept 5 days. ci.yml keeps running only the CI-sized test above, as part of `:core:desktopTest`.

## How it works

- `SyncSimulation` holds the replicas. Each one (`SyncSimulation.Member`) has its own
  `InMemoryLibraryFileSystem` (its own storage profile), its own `SimReplica` (an
  installation id, `index.db` and `device.db`, a seeded id source, a log that names it), its own
  `Simulation` (generator, background operations, invariants, rebuild oracle), and sometimes a
  watched inbox folder. A step is one replica's operation, from its `SimGenerator`, or one of the
  sync tool's.
- `SimSync` is the sync tool, modelled on Syncthing and its kind. A `scan` of one device records
  what changed in its folder since its last scan (a new file, other bytes, a deletion) as a version
  with a version vector, and sends it to every other device. A delivery lands after a seeded delay
  (mostly seconds, sometimes minutes), so deliveries arrive out of order; one in ten comes twice.
  It is written under the tool's temporary name (`NpnPaths.isSyncTemp`: Syncthing, Nextcloud or
  Resilio, per seed) and renamed into place, so it lands whole: no tool of this kind (Syncthing,
  Nextcloud, Dropbox, Resilio) writes a file in place at its final name. A file written in place
  over a while comes from another process on one device (`EXT_CHUNKED_WRITE`), which the tool then
  carries to the others whole. Journal objects, OCR sidecars, device markers, staging files and PDFs
  travel alike, with their modification times. Before it replaces a file the
  tool checks the device did not change it since its last scan. Two versions neither descends from are
  a conflict: identical bytes merge, a modification beats a deletion, otherwise the newer mtime (then
  the higher device id) keeps the name and the other is kept as a conflict copy (Syncthing's
  `.sync-conflict-<date>-<time>-<device>` or Nextcloud's `(conflicted copy <date> <time>)`), which
  then syncs like any new file. A device given the folder later (`SyncConfig.joinsAt`:
  a quarter of the replicas after the first, at a step in the first half of the run) starts with an
  empty folder, which the tool fills with the latest version of every path over the same delays,
  while the app opens on it at once, as a second device set up later is.
- A seed may sync its replicas through a cloud remote instead (`SyncConfig.cloud`, about three
  seeds in ten): one in-memory remote (`SimRemote`) behind each session's own cloud sync, the
  app's real `SyncEngine` and `SyncService`, as rclone is in production, and no sync tool. The seed
  decides whether the remote folds case and reports SHA-256 or Dropbox's content hash, and whether
  some of its calls fail. A pass makes several of its calls at once.
  Each call waits a seeded stretch of virtual time. A replica connects as a user sets one up, with
  a first pass before anything is added on it; a step is then a replica's pass run to its end, or
  one started beside whatever comes next (other passes, its own session's work, a crash), or time
  for passes under way. A full sync is rounds of a pass on every replica and settling, until a round
  after the replicas settled changes nothing: settling rescans, which may find a file no pass has
  carried yet (one another process wrote on storage no watcher reports), and only the round after it
  carries that. The remote's faults are off at rest. What a pass installs from the remote counts
  as delivered, not as the app's own write, and a copy on the remote keeps a document's last copy.
- On request (`-Dnpn.sim.bisync=<percent>`, drawn for that share of the seeds without a cloud), the
  folder sync tool is rclone bisync through one remote instead (`SimBisync`, behind the same
  `FolderSyncTool` as `SimSync`): the shape cloud sync would take if the app left the whole folder
  to `rclone bisync --compare size,modtime,checksum --conflict-resolve newer --conflict-loser num
  --resilient --recover --resync-mode newer`. It follows rclone v1.75.1's `cmd/bisync`: each run
  compares both sides with the listings the device saved after its last successful run, acts on
  that listing without looking again, renames a conflict's loser `<name>.conflict<n>`, aborts when
  more than half of a side's files were deleted, and is killed or fails part way in some seeds (a
  failed run drops the listings: the next one recovers from the backup, or resyncs). Bytes it takes
  that its listing did not show, or that a resync overwrites, are its own loss (`clobbered`), and
  the last-copy check fails a run on them. It is off by default because it fails: see below.
- A full sync (`SimSync.flush`) lets every delivery under way land, scans every device and delivers at
  once, and brings each device every version another holds that its own does not descend from, until
  a round changes nothing. Two devices recording different bytes under one version vector would be
  a slip of the model, never the tool's, and fail the run rather than being settled quietly.
- `InMemoryLibraryFileSystem` implements `LibraryFileSystem` with the semantics of
  `JvmLibraryFileSystem`: publication without replacement by hard link or exclusive rename
  (`StorageProfile`), and `sameFile` through hard links.
  Each call suspends at least once and sometimes waits a seeded stretch of virtual time
  (`SimLatency`). It records every journal object, snapshot, OCR sidecar and device marker the app
  creates, every file the app removes, the last changes at each path and who made them, and every
  time bytes left a path (`Losses`).
- Its `external*` members are the outside world: an atomic write, a write in place in chunks over
  virtual time (`cp`, a scanner app), an overwrite in place or by rename, a touch (a new mtime over
  the same bytes), a delete, a move, a backup (which skips a file still being written) put back,
  and sync-tool temporary files.
- `SimFaults` fails any filesystem method once or N times, at any path or under a prefix, before
  the call takes effect or after it landed.
- `SimNames` is the one place the outside world's names come from, derived from `NpnPaths` and
  `JournalPaths`: a sync tool's temporary name (checked against `NpnPaths.isSyncTemp`), what is a
  journal object, and what the library writes once. `LibraryFileSystemContractTest` runs one
  contract over `JvmLibraryFileSystem` and `InMemoryLibraryFileSystem`, so the fake cannot drift
  from the real filesystem silently.
- `SimWorld` puts all time on one `TestCoroutineScheduler`. The sessions' scopes, their computation
  dispatcher (`SessionDeps.computation`), the watch's time source, Room's query dispatcher, the sync
  tool and every operation run on one `SimDispatcher` over a `StandardTestDispatcher`. `EpochClock`
  reads the scheduler's time. So every settle window, the 15-minute maintenance tick,
  `RESTORE_SETTLE_MS`, `WriteSettle` and the queue backoffs pass only when the simulation advances
  time. An operation that does not end within six virtual hours is a hang.
- A session's scope carries a `SimLife`, and so does every coroutine it starts, Room's queries and
  computation included. `SimDispatcher` runs a coroutine only while its life goes on: a hard crash
  (`SimReplica.hardCrash`) pauses the process, takes images of both databases between two
  transactions (`VACUUM INTO`, as a killed process leaves them committed), and kills it: nothing of
  it runs again, not even a `finally`. The next open runs over the images. A soft crash cancels the
  scope, so `finally` blocks still run.
- The fakes are `SimPdfEngine` (any bytes starting `%PDF-` open; `text:` lines are the text layer,
  `pages:` the page count, so documents split; every split part is a new file, as PDFBox stamps a
  new document's `/ID` from the clock, so two replicas splitting one document make other parts),
  `SimXmpIO` (a bake writes the XMP packet as an `xmp:` line, so bake-in-place relinks),
  `GatedOcrEngine` (each page waits until the simulation lets it finish; the rebuild oracle's has no
  engine installed, so it runs no OCR), `SimWatchedFolder` (a watched inbox folder in memory, swept
  by the real `WatchedFolderImporter`) and in-memory thumbnails.
- `SimGenerator` picks an `OpKind` by weight, from one seeded stream per replica: the app's writes
  through the facets (a user's change to a seeded field among them: relabelled or removed), rescans,
  OCR completions, bake in place (also one whose move of the original aside lands but reports a
  failure while the app rescans back to back, so a rescan can list the original under its recovery
  name while the bake puts it back, and one whose moves into place and back are both refused),
  multi-page imports and split (also one whose trash of the
  original fails while the sync tool scans the replica part-way, so a part it takes back may be on
  the other devices already), the relink of a missing document to other bytes, auto-match rule saves, deletes and toggles (whose
  field sets drive the storage-path rule, which moves documents on every replica), the watched folder
  where the replica has one (new, slowly written, known, overwritten and removed files, and sweeps,
  beside the importer each session runs with delete-after-import on or off), several operations at
  once (with external events landing while they run), an operation left running in the background,
  time steps, every external event, faults, crashes (a hard one included), close and reopen, and
  dropping the index. Another device's journal objects and OCR sidecars are the sync tool's to
  deliver.

## Invariants

After every step, on every replica:

- No exception reaches a scope's handler.
- Nothing logged is a bug (`SimLog.isBug`): anything at `ERROR`, or a failure whose cause is a
  programming error (a failed `check` or `require`, null, bounds, casts, SQLite constraints).
  Expected failures pass: injected faults, a closed session, blocked writes, a refused publication,
  a file another process moved or replaced, a refused bake or a superseded import, and a bake
  displaced by a third file at its path while it was undone (`BakeDisplacedException`, logged as a
  warning, the original kept under a recovery name), whoever wrote that file.
- No operation throws such a bug either.
- Write-once: no object the app published changes bytes, and every journal object the app wrote is
  named by its hash.
- Index structure: every live row is at a document path, and a row waiting at a sentinel waits at
  its own (`missingSentinel`). OCR queue rows stay in their declared states, and neither their
  attempts nor the enrichment queue's go negative.

Across replicas, after every step:

- The sync tool never meets two versions of a name the library writes once (a journal object or
  snapshot, an OCR sidecar, a device marker): two writers published different bytes under one name.
- No replica deletes or moves away a journal object, nor another device's sidecar or marker.
- No two replicas share a device id.
- No replica blocks writes as `JOURNAL_DAMAGED`: objects are write-once and named by their hash, the
  tool delivers each whole by a rename and shows a file in transfer under its temporary name, and
  nothing deletes one, so a journal a library finds damaged is a bug.
- A watched folder's importer deletes only a file whose bytes the library has a document of: bytes
  a file in the replica's folder holds, or bytes a relink superseded whose final target, the relink
  chain followed to its end, a file in that folder holds. The importer proves the library holds the
  document and then deletes the source, two calls on two filesystems: when only the sync tool or
  another process took the document's bytes from the folder after its last proof (which held when
  it was made), and the app took none, the delete is no finding, as it would be no finding a moment
  later. A delete with no proof, or after the app itself took the bytes, is one.

At rest (every `quiesceEvery` steps, and at the end), deliveries under way land, faults are
cleared, OCR runs through and background operations end on every replica, the sync tool scans
every device once and lets those deliveries land (it runs through the day as well; frozen, it
would leave another device's trash unannounced, and the file still at its path would pass the
window as one come back), every replica rescans its folder, as the app does on Android at every
resume (on storage no watch reports on, nothing else would before the day ends), and then a virtual
day (`RESTORE_SETTLE_MS`) passes, so every trash, and every return those rescans found, is older than
the restore settle window by the rescans that follow: the app records the return of a trashed document's own bytes as a restore only once
that window has passed (`JournalCoordinator.recordRestores`), counted both from the trash and from
when the session's rescans first found the file back, and before it a rescan shows such a file for
that scan only. The day runs everything time brings (the 15-minute tick, the queues'
backoffs, and a trash purge after any rescan the watch starts). Then 20 virtual minutes pass and
every replica rescans. Half the time, and always at the end, that comes after a full sync, round
after round until a round leaves every folder equal (through the cloud: until a pass round after
settling changes nothing); replicas that keep changing their folders after six rounds (through the
cloud, eight) do not converge, which fails the run. Then:

- No replica blocks writes.
- On each replica: every live document's file holds its hash; every live row stores its file's
  size and mtime, the proof by stat the rescan, trash and import take from it (a file touched since
  was hashed and its row given the new stat); and every settled PDF in its folder is
  a live document, a known duplicate, or bytes a relink superseded (or, while doc-undelete is not
  written, a document deleted for good). A trashed document's own bytes back at its path is no
  exception: the rescan shows it, and records its restore once both windows have passed.
- Verify library holds: it reports nothing but documents whose files are gone from the folder (a
  document is missing only while no file at a document path there holds its bytes), and every
  document the library lists as missing is among them, named by its library path, never its
  internal sentinel. A document it reports missing that the index has no row of (the journal shows
  it, the index lost it) is never one whose last copy in the folder the app took itself. The
  outside world and the sync tool, carrying out another device's change, are the faults this
  allows; after a full sync, no replica's folder holds a document any replica reports missing.
- Every file in the trash holds the bytes of the document its name names (the hash prefix
  `NpnPaths.trashPath` gives it): one holding other bytes is a file the trash took under a
  document's name, hidden from the library for good, since no rescan indexes the trash and neither
  the purge nor a restore touches it. A name without that prefix (a conflict copy) is not judged.
- No bake's original lingers: no file under a recovery name holds bytes a relink superseded while
  the live document they became has its own bytes at its path. The bake deletes its original once
  its relink stands, and the rescan one a crash (the simulation's hard crash stops a bake at any
  suspension point) or a failed delete left behind.
- **Rebuild oracle**, on each replica: a fresh replica opens over a copy of its files with empty
  databases, rescans to rest, and must show the same library (`LibraryView`, `diff`). That covers
  live documents with their path, tags, field values and reviewed flag, the documents in the trash
  with the path each was trashed from and the same metadata, the deleted state of documents both
  indexes have, field definitions, the styles of the tags in use and every tag no document carries
  (a created tag, styled or not), review mode, storage path rule,
  trash retention, saved views and auto-match rules. Device-local data is left out on purpose:
  device id, queues, enrichment marks, thumbnails, OCR state and page counts, the creation date
  each document's PDF gives (`createdAt`, read from the file again), a row's stat, rows hidden as
  missing or deleted that only the live index remembers, and which of several
  identical files is a document's path. A replica that is behind (the sync tool has not brought it
  everything yet) is judged on what it has: its index must show what its own folder holds. The
  rebuild publishes nothing but its device marker, and its own OCR sidecar of a document the folder
  held no sidecar of (the live replica's runs of it failed until its queue gave up; the rebuild,
  with no engine, keeps the text layer). Anything else it publishes (a restore, a move, a field
  seeding) is a write the live replica owed the library, and fails the run. Its first rescan's trash
  purge deletes files in the trash and records nothing, so it changes nothing compared.
- What each replica wrote under a device's name in the library (sidecars, markers) is under its own.
- **The app never removes a document's last copy**: every document some replica's index has a row
  for, that no replica's journal deleted for good or relinked onto other bytes, and that no
  replica's folder holds a file of, was never taken by the app while it was the library's last
  copy. Each folder records every time bytes left a path (deleted, or replaced by other bytes; the
  same bytes written again take nothing): when, by whom (the app, in its trash or elsewhere; the sync
  tool, carrying out another device's change, so never the cause itself; or the outside world), and
  whether no other file held them, there or in another replica's folder. A copy another replica
  holds at the same path does not count, since the sync tool carries the change there and takes it
  too. Two kinds of file are none of the library's copies and count neither way: an import's staging
  file (a copy of the import's source) and a bake's temporary file (output no journal names until
  the bake's relink, which a failed bake discards). A bake's temporary file whose bytes a journal
  object in the folder names as a relink's target is a copy, though: a bake undone after its relink
  landed parks the document's bytes there. Any loss of the library's last copy by the app
  is a finding, whatever happened to the bytes before or after, but in its trash (the purge, a
  delete for good) for a document the replicas' journals, taken together, had in the trash at that
  time, or had restored after a purge already took a copy of it (the user restored past the
  retention), or had restored before another process took the restored file from its path (the user
  deleted what they had restored; once the restoring replica syncs, the tool carries that delete, or
  the restore's move out of the trash, to the other replicas' copies): a replica that had not seen
  the restore yet purges its copy by the retention too.
  A document whose only file the app deleted shows as missing in the live
  index and in a rebuild alike, so the rebuild oracle cannot see it.
- After a full sync, **convergence**: every replica shows the library the first one shows, by the
  same comparison, a row only one of them remembers for a document neither shows excepted.
- After a full sync, no replica shows a live document as missing while a folder's trash holds its
  bytes. A restore, an Undo or an import of the same bytes on a device that had another device's
  trash event but not yet its file move claims the file still at the document's path, which the
  move then takes away into the trash; so does a trash whose commit failed and whose file could not
  be put back. The rescan brings such a copy back (`StrandedTrashCopies`; the second kind after the
  restore settle window, which the day at rest lets pass). Left in the trash, it goes with the first
  purge on a device that had not seen the restore yet, the library's last copy.
- After a full sync, no seeded field a user relabelled or removed shows its seed's label again on
  any replica (no step writes a seed's label, so one back took a seed over the user's change).
- Last, the log and the scope handlers once more: the rebuild oracles log into the same world, and
  after the run's final rest no step's cheap check follows.

## Known issues

`KnownIssues` (in `Simulation.kt`) holds the switches and the known failures, each with a comment
that names the finding; a known failure is listed by the phrases its message holds. Fixing a finding
means turning its switch off (or removing its `knownFailures` entry) and making a long run pass.

- **The app writes no `doc-undelete` yet**, `KnownIssues.DELETED_RETURNS_UNRECORDED`: the
  documented cost of keeping builds from before the op working (`JournalOp.DocUndelete.WRITTEN`,
  ARCHITECTURE.md 6.4). A document deleted for good whose bytes come back is live on the device that
  imported them, for that session, and in the scan that finds the file, and nowhere else, so devices
  disagree, and a device disagrees with a rebuild of its own index. Off by itself once the op is
  written.

- **Cloud replicas may name a document differently**, `KnownIssues.CLOUD_NAMES_MAY_DIFFER`: cloud
  sync settles a document's name one rename at a time, each waiting while its name is taken here,
  so two documents that swapped names (or a chain of renames) stay apart. A cloud seed's
  convergence check compares the libraries without document paths, and traces the paths that
  differ. Off once renames are settled with a temporary name.

## Finding: rclone bisync loses documents

Run with `-Dnpn.sim.bisync=40` over seeds 1..80 (250 steps), nearly every bisync seed failed the
last-copy check (Syncthing-like and cloud seeds passed). Each loss is the only copy of a live
document, taken by bisync acting on a listing older than the file:

- **Two devices' runs at once** (about half): one lists the remote, the other uploads a new file at
  a path the first saw deleted locally; the first's deletion then removes the upload, and the
  uploader's next run deletes its own copy as a remote deletion. bisync locks a device's listings,
  not the remote.
- **The app writing during a run** (about a third): a file imported or moved into a path after the
  listing is replaced by the remote's copy, or deleted as a deletion the remote made.
- **A resync** (the rest; the majority once runs never overlap): every device's first run, and a
  run after a failed one with no backup listing, copies the newer of two different files at one
  path over the older (two devices that each imported `inbox/scan.pdf` before their first sync).

With runs made exclusive (no two devices at once), the app's writes during a run and resyncs still
lost documents in about two thirds of the bisync seeds. The cloud sync engine (`core.sync`) checks
every remote move and delete against a fresh `stat` and every local one against the file's hash,
never deletes or replaces to settle a conflict, and treats a missing remote file as unknown, which is
what these losses lack.

## Adding an operation

1. Add an `OpKind` with a weight.
2. Plan it in `SimGenerator.plan`. Use a `Planned.Act` for a call into
   the session (it may run concurrently or in the background, so add it to `CONCURRENT_KINDS` if it
   should). Use a `Planned.Control` for something done to the world, marked `instant` when it needs
   no scheduler (an external file change, a fault), so it can land during a group (`DURING_KINDS`).
   Draw every choice from the generator's `random`, and describe the step fully in its text, since
   the trace is all a failure shows.
3. If the outcome can be an expected failure the classifier does not know, teach `isBugFailure`
   why. Do not catch it in the operation.

## Adding an invariant

Cheap checks on one replica (a query or a walk over the in-memory files) go in
`Simulation.checkCheap`; checks across replicas in `SyncSimulation.checkAcross`. Checks that only
hold at rest go in `Simulation.checkAtRest` or `SyncSimulation.quiesce`. Rebuild-comparable state goes
in `LibraryView`, together with a line in `diff`. Leave out only what a rebuild cannot know, and say
why next to it. Fail through `fail(...)`, which attaches the seed and the trace.

## What it does not model yet

- **A hard crash inside a transaction.** A hard crash waits for an instant between transactions
  (`SimReplica.hardCrash`); one inside a transaction would roll it back, which is the database of a
  moment before, but what the process did to files during the transaction would stay. Modelling
  that needs file-backed databases copied at the crash point.
- **The sync tool losing a file it delivered**, or a device that never syncs again. The tool here
  delivers everything eventually, and never damages a file: it writes nothing in place.
- **Directory renames and deletions** by the sync tool: it syncs files, and folders follow them.
- **Devices that leave**, or come back after a long time away: a replica that has joined takes part
  to the end.
- **AI** (enrichment, edit runs, chat, AI OCR): the AI endpoint is disabled.
- **Snapshots.** The app no longer compacts the journal: it writes no snapshot and deletes no
  object, and reads the snapshots earlier builds wrote. The simulation makes none, so it does not
  exercise folding a snapshot beside the objects it replaced; `CompactionReconcileTest`,
  `OlderBuildSnapshotTest` and `UndoneCompactionTest` do.
- **Mixed-build libraries**: another build's writes and fold, and a build newer than this one (an
  op or a field type this build refuses), are not modelled: every replica runs this build. Two of
  0.1.0's tag writes are, as operations of their own: a style left on a name nothing carries
  (`LEGACY_UNUSED_STYLE`) and a rename of a created tag that leaves its marker behind
  (`LEGACY_RENAME_TAG`).
- **mtime-preserving overwrites** of the same size: every generated PDF has a size of its own, and
  an overwrite in place gives the file a new mtime. No two files of one size are given one mtime, in
  any replica's folder (`SimMtimes`): two of them with one stat, one renamed over the other, would be
  such an overwrite.
- **The app's temporary files under one name on several devices**: a bake writes
  `<document>.npnbake.tmp`, which the sync tool copies to other devices like any file, where it can
  replace the temporary file of a bake of the same document under way there. The simulation counts
  the bake that then fails to verify as refused, an expected outcome, and does not yet check that a
  bake installs only its own bytes.
