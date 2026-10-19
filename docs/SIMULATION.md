# Library simulation

`LibrarySimulationTest` (`core/src/desktopTest/kotlin/dev/npn/core/sim/`) runs a real library
session, the same `openSession` and `start()` the apps use, against an in-memory library folder, on
virtual time, driven by a seeded generator of operations. After each step it checks the library's
invariants. At rest, it compares the live index with one rebuilt from the files alone. A run is
reproducible from its seed. The bugs the session, journal, index and lifecycle code have had were
interleavings: a rescan racing a trash, a file replaced between a stat and a hash, a journal object
arriving mid-operation, a settle timer firing at the wrong moment. The simulation explores those
schedules on purpose instead of pinning one per example test.

## Running it

```
# What CI runs (in :core:desktopTest): seeds 1..10, 250 steps each, about 12 s on a laptop.
./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest'

# A long run: seeds 1..500, 400 steps each (or start elsewhere with -Dnpn.sim.firstSeed=).
./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest' -Dnpn.sim.seeds=500 -Dnpn.sim.steps=400

# Replay one seed (a failure prints this line with its seed and step count).
./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest' -Dnpn.sim.seed=1234 -Dnpn.sim.steps=400
```

`core/build.gradle.kts` forwards `npn.sim.seed`, `npn.sim.seeds`, `npn.sim.firstSeed`,
`npn.sim.steps` and `npn.sim.traceDir` to the test JVM, given as `-D` or `-P`. A changed value
reruns the test task instead of reusing its cached result. With `-Dnpn.sim.traceDir=<dir>`, every
seed writes its whole trace to `<dir>/seed-<n>.txt`, whether it fails or not. Comparing two such
files is how you check a replay.

A failing run stops at its first broken invariant. Its report has the seed, the configuration the
seed picked (storage profile, mtimes, watcher losses), the faults still armed, the last steps of
the trace, the end of the session log, the library's files with their hashes, and the index's
document rows. A run reports at most three failing seeds before it stops. A seed that hits a
failure listed in `KnownIssues.knownFailures` (a finding already written up) stops there. Such a
seed is counted and printed instead of failing the test, so a long run goes on to find new bugs. A
replay of that one seed still fails.

A seed decides everything the simulation chooses: the configuration, every operation and its
arguments, the latency of every filesystem call (which decides how concurrent work interleaves),
and which watch events are lost. One thing it cannot decide: core names temporary files, journal
actors and device ids with `Uuid.random()`. A replay therefore takes the same steps with other
names. A replay takes the same operations with the same arguments, but the virtual times can drift
by a few milliseconds once enough of those names have been sorted or hashed. In practice the
failures found so far replayed at the same step.

`SimulationHarnessTest` checks that the harness itself can fail. It changes the files the rebuild
opens, rewrites a published journal object, and logs a programming error, and expects each to fail
the run. It also checks that storage with no publication primitive refuses the library.

## How it works

- `InMemoryLibraryFileSystem` implements `LibraryFileSystem` with the semantics of
  `JvmLibraryFileSystem`, or of `SafLibraryFileSystem` under `StorageProfile.SAF`: publication
  without replacement by hard link or exclusive rename, `sameFile` through hard links, no document
  relocation and no watcher on SAF, and listings with no mtimes when `SimFsOptions.mtimes` is off.
  Each call suspends at least once and sometimes waits a seeded stretch of virtual time
  (`SimLatency`). It records every journal object, snapshot, OCR sidecar and device marker the app
  creates, and flags one that later holds other bytes or whose name is not its hash.
- Its `external*` members are the outside world: an atomic write, a write in place in chunks over
  virtual time (`cp`, a scanner app), an overwrite in place or by rename, a touch (a new mtime over
  the same bytes), a delete, a move, a backup put back, sync-tool temporary files, and a Syncthing-style delivery (temporary name, then
  rename). The generator uses that delivery for another device's journal objects and OCR
  sidecars.
- `SimFaults` fails any filesystem method once or N times, at any path or under a prefix, before
  the call takes effect or after it landed. It subsumes the app tests' `FileSystemFaults`.
- `SimWorld` puts all time on one `TestCoroutineScheduler`. The session scope, its computation
  dispatcher (`SessionDeps.computation`), the watch's time source (`SessionDeps.timeSource`), Room's
  query dispatcher and every operation run on one `StandardTestDispatcher`. `EpochClock` reads the
  scheduler's time. So every settle window, the 15-minute maintenance tick, `RESTORE_SETTLE_MS`,
  `WriteSettle` and the queue backoffs pass only when the simulation advances time. Operations run
  outside any session, and the simulation drives the scheduler in small slices until they end. An
  operation that does not end within six virtual hours is a hang.
- `SimReplica` is one device: its in-memory `index.db` and `device.db`, which outlive a session,
  and the session over them. It can crash (its scope is cancelled with no `close()`; files and
  databases stay), close, and drop its index. The fakes are `SimPdfEngine` (any bytes starting
  `%PDF-` open; `text:` lines are the text layer), `GatedOcrEngine` (each page waits until the
  simulation lets it finish, so OCR stays in flight across other steps) and in-memory thumbnails.
- `SimGenerator` picks an `OpKind` by weight: the app's writes through the facets, rescans, OCR
  completions, several operations at once (with external events landing while they run), an
  operation left running in the background, time steps (seconds, a tick, a day), every external
  event, faults, crashes, close and reopen, and dropping the index.

## Invariants

After every step:

- No exception reaches a scope's handler.
- Nothing logged is a bug (`SimLog.isBug`): anything at `ERROR`, or a failure whose cause is a
  programming error (a failed `check` or `require`, null, bounds, casts, SQLite constraints).
  Expected failures pass: injected faults, a closed session, blocked writes, a refused publication,
  a file another process moved or replaced (`NoSuchFileException`, "Target already exists").
- No operation throws such a bug either.
- Write-once: no recorded object changes bytes, and every journal object's name is its hash.
- Index structure: every live row is at a document path, and a row waiting at a sentinel waits at
  its own (`missingSentinel`). The schema already makes each path and each hash unique. OCR queue
  rows stay in their declared states (no `running`), and attempts never go negative.

At rest (every `quiesceEvery` steps, and at the end): faults are cleared, OCR runs through,
background operations end, 20 virtual minutes pass, then one more rescan runs. Then:

- Every live document's file holds its hash.
- Every live row stores its file's size and mtime, the proof by stat the rescan, trash and import
  take from it: a file touched since was hashed and its row given the new stat.
- Every settled PDF in the folder is a live document or a known duplicate.
- **Rebuild oracle**: a second replica opens over a copy of the files with empty databases,
  rescans to rest, and must show the same library (`LibraryView`, `diff`). That covers live
  documents with their path, tags, field values and reviewed flag, the documents in the trash with
  the path each was trashed from and the same metadata, the deleted state of documents both indexes
  have, field definitions, the styles of the tags live and trashed documents use, review mode, storage
  path rule, trash retention, saved views and auto-match rules. Device-local data is left out on
  purpose: device id, queues, enrichment marks, thumbnails, OCR state and page counts, `addedAt`, a
  row's stat, rows hidden as missing or deleted that only the live index remembers, and which of
  several identical files is a document's path (the rest are duplicates).

## Known issues

`KnownIssues` (in `Simulation.kt`) holds the switches that keep the suite green around findings
that are not fixed yet. Each switch's comment names the finding. Fixing a finding means turning
its switch off (or removing its `knownFailures` entry) and making a long run pass. None is set
aside at the moment.

## Adding an operation

1. Add an `OpKind` with a weight.
2. Plan it in `SimGenerator.plan`. Use a `Planned.Act` for a call into the session (it may run
   concurrently or in the background, so add it to `CONCURRENT_KINDS` if it should). Use a
   `Planned.Control` for something done to the world, marked `instant` when it needs no scheduler
   (an external file change, a fault), so it can land during a group (`DURING_KINDS`). Draw every
   choice from the generator's `random`, and describe the step fully in its text, since the trace
   is all a failure shows.
3. If the outcome can be an expected failure the classifier does not know, teach `isBugFailure`
   why. Do not catch it in the operation.

## Adding an invariant

Cheap checks (a query or a walk over the in-memory files) go in `Simulation.checkCheap`. Checks
that only hold at rest go in `quiesceAndCheck`. Rebuild-comparable state goes in `LibraryView`,
together with a line in `diff`. Leave out only what a rebuild cannot know, and say why next to it.
Fail through `fail(...)`, which attaches the seed and the trace.

## What it does not model yet

- **More than one replica.** `SimReplica` is the extension point. A multi-replica run gives each
  device its own `InMemoryLibraryFileSystem` and adds a sync process that copies files between them
  with delays, conflicts and temporary names (the external API already delivers files that way).
  Then it checks convergence after a full sync, in place of or beside the rebuild oracle. Today,
  "another device" only means journal objects and OCR sidecars delivered from outside.
- **A hard crash.** A crash cancels the session's scope, so `finally` blocks and `NonCancellable`
  cleanups still run, and Room rolls back. A killed process runs none of that. Simulating it needs
  file-backed databases copied at the crash point.
- **Bake in place, split, relink, the watched inbox folder, AI** (enrichment, edit runs, chat, AI OCR)
  and auto-match rule edits. The AI endpoint is disabled, and the XMP writer refuses.
- **Compaction** runs only when the maintenance tick finds more than 64 objects older than a day,
  which a long run reaches only now and then. There is no direct compaction operation, because the
  coordinator is internal to the session.
- **mtime-preserving overwrites** of the same size: with mtimes, every generated PDF has a size of
  its own. Where storage reports no mtimes, same-size replacements are modelled: every generated PDF
  has one of a few sizes, so only a hash tells a replacement apart.
- **Deterministic names.** See the `Uuid.random()` note above.
