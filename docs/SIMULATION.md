# Library simulation

`LibrarySimulationTest` (`core/src/desktopTest/kotlin/dev/npn/core/sim/`) runs two to four replicas
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
./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest'

# A long run: seeds 1..300, 400 steps each (or start elsewhere with -Dnpn.sim.firstSeed=), every
# failing seed reported rather than the first three.
./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest' -Dnpn.sim.seeds=300 -Dnpn.sim.steps=400 -Dnpn.sim.maxReported=300

# Replay one seed (a failure prints this line with its seed and step count).
./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest' -Dnpn.sim.seed=1234 -Dnpn.sim.steps=400
```

`core/build.gradle.kts` forwards `npn.sim.seed`, `npn.sim.seeds`, `npn.sim.firstSeed`,
`npn.sim.steps`, `npn.sim.traceDir`, `npn.sim.replicas` (force the number of replicas; 1 runs one
replica with every operation and no sync) and `npn.sim.maxReported` to the test JVM, given as `-D` or `-P`. A changed value reruns the test task
instead of reusing its cached result. With `-Dnpn.sim.traceDir=<dir>`, every seed writes its whole
trace to `<dir>/seed-<n>.txt`, whether it fails or not. The test prints each seed's real time, and
any step that took over a second.

A seed decides everything the simulation chooses: the replicas and their storage, the sync tool's
behaviour, every operation and its arguments, the latency of every filesystem call (which decides how
concurrent work interleaves), which watch events are lost, and every name core draws:
`SessionDeps.ids` is the session's source of journal actor ids, staging, import and recovery file
names, device ids and rule ids, and the simulation seeds it (`SimIds`). So a replay is the same run:
the same steps at the same virtual times, line for line in the trace.
`SyncSimulationHarnessTest.aSeedReplaysTheSameVirtualTimeline` checks that.

A failing run stops at its first broken invariant. Its report has the seed, the configuration (each
replica's storage profile and mtimes, the watcher's losses, the sync tool), the faults still armed,
the last steps of the trace, the end of the session log (each entry names its replica), the failing
replica's files with their hashes and its index's document rows, the history of a path whose file is
not its document's, the journal events behind a document the rebuild shows otherwise, and the sync
tool's state (conflict copies, deliveries under way, damage it did). A seed that hits a failure listed
in `KnownIssues.knownFailures` or `SyncKnownIssues.knownFailures` (a finding already written up) stops
there. Such a seed is counted and printed instead of failing the test, so a long run goes on to find
new bugs. A replay of that one seed still fails.

`SimulationHarnessTest` and `SyncSimulationHarnessTest` check that the harness itself can fail: the
rebuild opening other files, a rewritten journal object, a logged programming error, two versions of
a write-once name meeting in the sync tool, a replica deleting a journal object, and a damaged journal
nothing in the simulation explains each fail the run. Storage with no publication primitive refuses
the library.

## Scheduled runs

`.github/workflows/simulation.yml` runs the simulation nightly on fresh seeds (each night the next
`NIGHTLY_SEEDS` from 1000 on, by run number, so it never repeats CI's seeds 1..10), and on demand
with `seeds`, `steps` and `first_seed` inputs. `.github/scripts/simulation-seeds.sh` picks the seeds.
A seed of 400 steps costs about 6.6 s on a laptop (300 seeds took 33 minutes) and, at an assumed
1.5 times that on a two-core hosted runner, about 10 s there, so the nightly 60 seeds of 400 steps take
about ten minutes of simulation, under a 30-minute step timeout and a 40-minute job timeout: some 15
Actions minutes a night with checkout and the build, about 450 a month. When a seed fails, the run uploads every seed's trace and the test report as an artifact
kept 5 days. ci.yml keeps running only the CI-sized test above, as part of `:core:desktopTest`.

## How it works

- `SyncSimulation` holds the replicas. Each one (`SyncSimulation.Member`) has its own
  `InMemoryLibraryFileSystem` (its own storage profile and mtimes), its own `SimReplica` (an
  installation id, `index.db` and `device.db`, a seeded id source, a log that names it), its own
  `Simulation` (generator, background operations, invariants, rebuild oracle), an `ExtraPlanner`, and
  sometimes a watched inbox folder. A step is one replica's operation, from its `SimGenerator` or its
  `ExtraPlanner`, or one of the sync tool's.
- `SimSync` is the sync tool, modelled on Syncthing and its kind. A `scan` of one device records
  what changed in its folder since its last scan (a new file, other bytes, a deletion) as a version
  with a version vector, and sends it to every other device. A delivery lands after a seeded delay
  (mostly seconds, sometimes minutes), so deliveries arrive out of order; one in ten comes twice.
  It is written under the tool's temporary name (`NpnPaths.isSyncTemp`: Syncthing, Nextcloud or
  Resilio, per seed) and renamed into place, or, for some PDFs on some seeds, written in place over a
  few seconds, partial at its final name meanwhile. Journal objects, OCR sidecars, device markers,
  staging files and PDFs travel alike, with their modification times. Before it replaces a file the
  tool checks the device did not change it since its last scan. Two versions neither descends from are
  a conflict: identical bytes merge, a modification beats a deletion, otherwise the newer mtime (then
  the higher device id) keeps the name and the other is kept as a conflict copy (Syncthing's
  `.sync-conflict-<date>-<time>-<device>` or Nextcloud's `(conflicted copy <date> <time>)`), which
  then syncs like any new file. On some seeds the tool also writes journal objects in place, which
  a library cannot tell from damage; the tool records each such delivery (`damagedAt`).
- A full sync (`SimSync.flush`) lets every delivery under way land, scans every device and delivers at
  once, and brings each device every version another holds that its own does not descend from, until
  a round changes nothing.
- `InMemoryLibraryFileSystem` implements `LibraryFileSystem` with the semantics of
  `JvmLibraryFileSystem`, or of `SafLibraryFileSystem` under `StorageProfile.SAF`: publication
  without replacement by hard link or exclusive rename, `sameFile` through hard links, no document
  relocation and no watcher on SAF, and listings with no mtimes when `SimFsOptions.mtimes` is off.
  Each call suspends at least once and sometimes waits a seeded stretch of virtual time
  (`SimLatency`). It records every journal object, snapshot, OCR sidecar and device marker the app
  creates, every file the app removes, the last changes at each path and who made them, and the
  last write at each path: when, the app's or the outside world's, and the bytes it left.
- Its `external*` members are the outside world: an atomic write, a write in place in chunks over
  virtual time (`cp`, a scanner app), an overwrite in place or by rename, a touch (a new mtime over
  the same bytes), a delete, a move, a backup (which skips a file still being written) put back,
  and sync-tool temporary files.
- `SimFaults` fails any filesystem method once or N times, at any path or under a prefix, before
  the call takes effect or after it landed.
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
  `SimXmpIO` (a bake writes the XMP packet as an `xmp:`
  line, so bake-in-place relinks), `GatedOcrEngine` (each page waits until the simulation lets it
  finish), `SimWatchedFolder` (a watched inbox folder in memory, swept by the real
  `WatchedFolderImporter`) and in-memory thumbnails.
- `SimGenerator` picks an `OpKind` by weight: the app's writes through the facets, rescans, OCR
  completions, several operations at once (with external events landing while they run), an
  operation left running in the background, time steps, every external event, faults, crashes, close
  and reopen, and dropping the index. Among the external events is a device on an older build
  (`main`), which records a relink with no copies and folds a target over its predecessors: its bare
  `relink` of one live document onto another arrives like any other device's journal object. `ExtraPlanner` adds bake in place, multi-page imports and split,
  the relink of a missing document to other bytes, auto-match rule saves, deletes and toggles (whose
  field sets drive the storage-path rule, which moves documents on every replica), the watched folder
  (new, slowly written, known, overwritten and removed files, and sweeps, beside the importer each
  session runs with delete-after-import on or off) and a hard crash, and its operations run beside
  one of the first generator's.

## Invariants

After every step, on every replica:

- No exception reaches a scope's handler.
- Nothing logged is a bug (`SimLog.isBug`): anything at `ERROR`, or a failure whose cause is a
  programming error (a failed `check` or `require`, null, bounds, casts, SQLite constraints).
  Expected failures pass: injected faults, a closed session, blocked writes, a refused publication,
  a file another process moved or replaced, a refused bake or a superseded import. A bake displaced
  by a third file at its path while it was undone (`BakeDisplacedException`, logged as a warning, the
  original kept under a recovery name) passes only when the file history shows that file came from
  outside the app (another process, the sync tool); the app's own file there is a bug.
- No operation throws such a bug either.
- Write-once: no object the app published changes bytes, and every journal object the app wrote is
  named by its hash.
- Index structure: every live row is at a document path, and a row waiting at a sentinel waits at
  its own (`missingSentinel`). OCR queue rows stay in their declared states, and attempts never go
  negative.

Across replicas, after every step:

- The sync tool never meets two versions of a name the library writes once (a journal object or
  snapshot, an OCR sidecar, a device marker): two writers published different bytes under one name.
- No replica deletes or moves away a journal object, nor another device's sidecar or marker.
- No two replicas share a device id.
- A replica blocks writes as `JOURNAL_DAMAGED` only after the sync tool wrote a journal object in
  place there since it was last at rest. Anything else
  that blocks writes is a bug: objects are write-once and named by their hash, and a sync tool's
  file in transfer shows under its temporary name.
- A watched folder's importer deletes only a file whose bytes the library has a document of.

At rest (every `quiesceEvery` steps, and at the end), deliveries under way land, faults are
cleared, OCR runs through, background operations end, 20 virtual minutes pass, and every replica
rescans. Half the time, and always at the end, that comes after a full sync, round after round
until a round leaves every folder equal; replicas that keep changing their folders after six rounds
do not converge, which fails the run. Then:

- No replica blocks writes.
- On each replica: every live document's file holds its hash; every live row stores its file's
  size and mtime, the proof by stat the rescan, trash and import take from it (a file touched since
  was hashed and its row given the new stat); and every settled PDF in its folder is
  a live document, a known duplicate, or bytes a relink superseded (or, while doc-undelete is not
  written, a document deleted for good). A trashed document's own bytes back at its path, or the
  path it was trashed from, within `RESTORE_SETTLE_MS` of the trash pass too, but only when another
  replica trashed it (the sync tool has not delivered that device's move of the file yet) or the
  last write at that path since the trash came from outside the app (another process, a backup put
  back, the sync tool): the app records such a return only once the window has passed. The app's
  own trash whose file the app itself put back is a finding.
- **Rebuild oracle**, on each replica: a fresh replica opens over a copy of its files with empty
  databases, rescans to rest, and must show the same library (`LibraryView`, `diff`). That covers
  live documents with their path, tags, field values and reviewed flag, the documents in the trash
  with the path each was trashed from and the same metadata, the deleted state of documents both
  indexes have, field definitions, the styles of the tags in use, review mode, storage path rule,
  trash retention, saved views and auto-match rules. Device-local data is left out on purpose:
  device id, queues, enrichment marks, thumbnails, OCR state and page counts, `addedAt`, a row's
  stat, rows hidden as missing or deleted that only the live index remembers, and which of several
  identical files is a document's path. A replica that is behind (the sync tool has not brought it
  everything yet) is judged on what it has: its index must show what its own folder holds. A
  document the live index has in the trash may show in the rebuild only where the check above lets
  its bytes be back.
- **Relink copies**: every relink target shows on its own what an older build shows it by
  inheritance over its relink chain (`RelinkInheritanceUpgrade.copies` of the replica's fold is
  empty), at rest and already before the quiescent rescan, once the watch and the periodic
  reconcile have folded everything delivered: the session copies after a watch ingest or reconcile
  as after a rescan. Before the rescan a target is passed over whose predecessor the replica itself
  edited after the relink, which this build copies only at the next rescan.
- What each replica wrote under a device's name in the library (sidecars, markers) is under its own.
- **The app never removes a document's last copy**: every document some replica's index has a row
  for, that no replica's journal deleted for good or relinked onto other bytes, is held by a file in
  some replica's folder, unless another process took its bytes away, or the app did in its trash
  (the purge, a delete for good). Each folder records who took which bytes from a path: the app,
  the sync tool (carrying out another device's change, so never the cause itself), or the outside
  world. A document whose only file the app deleted shows as missing in the live index and in a
  rebuild alike, so the rebuild oracle cannot see it.
- After a full sync, **convergence**: every replica shows the library the first one shows, by the
  same comparison, a row only one of them remembers for a document neither shows excepted.

## Known issues

`KnownIssues` (in `Simulation.kt`) holds the single replica's switches and known failures;
`SyncKnownIssues` (in `SimReplicas.kt`) holds the ones only several replicas find. Each entry's
comment names the finding. Fixing a finding means turning its switch off (or removing its
`knownFailures` entry) and making a long run pass.

- **Finding 8**, `SyncKnownIssues.DELETED_RETURNS_UNRECORDED`: the documented cost of `doc-undelete`
  not being written yet (ARCHITECTURE.md 6.4). A document deleted for good whose bytes come back is
  live on the device that imported them, for that session, and in the scan that finds the file, and
  nowhere else, so devices disagree, and a device disagrees with a rebuild of its own index. Off by
  itself once the op is written.

## Adding an operation

1. Add an `OpKind` with a weight (or an `ExtraKind`, for an operation only several replicas, or a
   world beyond the folder, make sense of).
2. Plan it in `SimGenerator.plan` (or `ExtraPlanner.planners`). Use a `Planned.Act` for a call into
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
  delivers everything eventually; it damages only by writing a journal object in place for a while.
- **Directory renames and deletions** by the sync tool: it syncs files, and folders follow them.
- **Devices that join late** with an empty folder, or leave: every replica opens at step 0.
- **Partial listings** from a document provider still loading (`DocumentsContract.EXTRA_LOADING`):
  SAF storage is on its way out, so the simulation spends nothing on what only it can do. The SAF
  profile stays for its publication and relocation rules while the app supports it.
- **AI** (enrichment, edit runs, chat, AI OCR): the AI endpoint is disabled.
- **Snapshots.** The app no longer compacts the journal: it writes no snapshot and deletes no
  object, and reads the snapshots earlier builds wrote. The simulation makes none, so it does not
  exercise folding a snapshot beside the objects it replaced; `CompactionReconcileTest`,
  `OlderBuildSnapshotTest` and `UndoneCompactionTest` do.
- **Mixed-build libraries**, beyond one thing an older build does: a device on `main` records a
  relink with no copies and folds a target over its predecessors, and the generator delivers such a
  bare relink (`EXT_OLDER_BUILD_RELINK`). That device's other writes, its fold, and a build newer
  than this one (an op or a field type this build refuses) are not modelled: every replica runs
  this build.
- **mtime-preserving overwrites** of the same size: with mtimes, every generated PDF has a size of
  its own. Where storage reports no mtimes, same-size replacements are modelled: every generated PDF
  has one of a few sizes, so only a hash tells a replacement apart.
- **The app's temporary files under one name on several devices**: a bake writes
  `<document>.npnbake.tmp`, which the sync tool copies to other devices like any file, where it can
  replace the temporary file of a bake of the same document under way there. The simulation counts
  the bake that then fails to verify as refused, an expected outcome, and does not yet check that a
  bake installs only its own bytes.
