# Mutation testing

Line coverage (kover) says a line ran. It does not say a test would notice the line being wrong.
Mutation testing measures that: [PIT](https://pitest.org) makes one small change to the compiled
code at a time (a negated or removed condition, a changed boundary, a removed call, a constant
return), runs the tests that cover the changed line, and records whether any of them failed. A
change no test notices is a *surviving mutant*: a place where the code could be wrong and the suite
would still pass.

It runs over `core`'s `desktop` target, where the session, coordinator, indexer, import and
lifecycle code lives and where most of the review rounds' bug fixes landed. It is slow, so it never
runs in `check`, `allTests` or `ci.yml`: run it on demand, and `.github/workflows/mutation.yml`
runs it weekly (Sunday) and on `workflow_dispatch`, uploading the HTML report as the
`mutation-report` artifact for a week.

## Running it

```
# The default: dev.npn.core.index and dev.npn.core.app (with dev.npn.core.app.aiedit).
./gradlew :core:mutationTest
mise mutation

# Other packages, each with its subpackages; a comma-separated list.
./gradlew :core:mutationTest -Pnpn.mutation.packages=dev.npn.core.journal
mise mutation dev.npn.core.journal

# The number of test processes; the default is half the cores.
./gradlew :core:mutationTest -Pnpn.mutation.threads=4
```

The report is `core/build/reports/pitest/index.html`, with `mutations.xml` beside it for scripts.
Each test process takes up to 768 MB of heap on top of Gradle's, so on a machine that is short of
memory, or one building something else at the same time, lower the thread count rather than raise it.

The default scope leaves `dev.npn.core.journal` out because a run of all three packages takes well
over an hour on a laptop; mutate it on its own when working on the journal (15 to 20 minutes).

## How it is set up

- `MutationTestTask` (`buildSrc`) runs PIT's command-line jar (`pitest` in
  `gradle/libs.versions.toml`) as a `JavaExec`. The pitest Gradle plugin assumes a plain JVM
  project; `core` is multiplatform, so `core/build.gradle.kts` hands the task the `desktop` main
  compilation's class directories (what may be mutated), the `desktopTest` task's classpath (what
  the tests run with) and the compilation's source directories (for the report).
- Tests: every test class under `dev.npn.core` that `:core:desktopTest` runs (JUnit 4), so a
  session test can kill a mutant in the indexer. PIT runs, per mutant, only the tests that cover
  its line, fastest first, and stops at the first failure.
- Mutators: PIT's `DEFAULTS` group (since 1.20 it removes conditions instead of negating them,
  which produces fewer equivalent mutants).
- Kotlin noise left out:
  - calls into `kotlin.jvm.internal` (the `Intrinsics` null checks on parameters and platform
    types) and `kotlin.ResultKt` (the coroutine state machine's result check) are never removed;
  - the generated members of data classes (`equals`, `hashCode`, `toString`, `componentN`, `copy`)
    are not mutated; the target packages write none of these by hand;
  - generated classes are not mutated: kotlinx.serialization's `$$serializer`, Room's `_Impl`, the
    `$WhenMappings` and `$EntriesMappings` lookup tables;
  - PIT's own Kotlin filter (on by default) drops mutants on compiler-generated code without a line.
- A mutant can crash a test process in native code (the bundled SQLite); PIT counts that as a kill.
  The crash log goes to `core/build/tmp/mutationTest/`, and no core dump is written.

Excluded test classes, and why:

- `dev.npn.core.sim.*` (the library simulation and its harness test): its steps run concurrently,
  so whether it kills a mutant can change between runs, and it is slow by design. The simulation
  is a different measure of the same code; see [SIMULATION.md](SIMULATION.md).
- `dev.npn.core.ai.LiveEndpointProbeTest`: it calls a paid endpoint when `NPN_LIVE_AI` is set and
  is skipped otherwise.

`ReconcilePerfTest` stays in: despite its name it counts reads, not time, and it is fast.

## Baseline (October 2026)

Measured on a 16-core laptop with `-Pnpn.mutation.threads=4`, PIT 1.30.1. The score counts
timed-out mutants as detected; strength leaves out the mutants no test covers. "Inlined" counts the
survivors on a line past the end of their file (see below).

`dev.npn.core.journal`, a complete run (15 min 27 s; 47 s of it the coverage pass), made just
before a fix added six lines to `FoldedState`:

| Package | Mutants | Killed | Survived (inlined) | Timed out | No coverage | Score | Strength |
|---|---|---|---|---|---|---|---|
| `core.journal` | 639 | 456 | 142 (28) | 0 | 41 | 71.4% | 76.3% |

A rerun of `dev.npn.core.journal` after that fix (19 min 7 s) matched it: 647 mutants, 463
killed, 146 survived, 0 timed out, 38 without coverage, a score of 71.4% → 71.6% and a strength of
76.3% → 76.0%.

`dev.npn.core.index` and `dev.npn.core.app` (with `core.app.aiedit`), a complete run of the default
scope: 56 min 37 s, 54,762 test runs (16.3 per mutant).

| Package | Mutants | Killed | Survived | Timed out | No coverage | Score | Strength |
|---|---|---|---|---|---|---|---|
| `core.index` | 1493 | 994 | 274 | 112 | 113 | 74.1% | 80.1% |
| `core.app` | 1487 | 802 | 279 | 106 | 299 | 61.1% | 76.4% |
| `core.app.aiedit` | 374 | 275 | 70 | 4 | 24 | 74.6% | 79.7% |
| Total | 3354 | 2071 | 623 | 222 | 436 | 68.4% | 78.6% |

Per class, most first, the survivors (and mutants without coverage) were: `JournalCoordinator` 80
(68), `LibrarySessionImpl` 62 (26; a score of 42.9%), `ImportService` 43 (12), `Indexer` 35 (3),
`MetadataWriterImpl` 32 (35), `FoldProjection` 25 (4), `MetadataAccumulator` 24 (4),
`DocLifecycleImpl` 23 (17), `NameSimilarity` 23 (3), `AiEditQueue` 22 (9), `FieldRegistryImpl` 15
(19), `DocContentFacadeImpl` 14 (27). In `core.journal`: `FoldedState` 39 (14),
`EventDecoderRegistry` 38 (1), `JournalEventSerializer` 29 (1), `StorageV2Reader` 12 (3),
`SnapshotCodec` 7 (0).

A complete run of both default packages takes 57 minutes at four threads on that laptop; run it
alone, or on the weekly workflow. `mutation.yml` runs it at two threads in a 165-minute step, which
is likely too short on a two-core hosted runner: half the threads alone make it about two hours
there before any slowdown of the runner itself.

### Likely test gaps in the baseline, now covered

The baseline's survivors in the hotspot classes held ten guards no test exercised. Each now has a
test that fails with the mutant applied by hand, and the complete run above includes them:

| Guard | Test |
|---|---|
| `Indexer.reconcileKnownHash` (now `RescanDecision.file`): refresh a row whose missing flag, size or mtime drifted | `IndexerTest`: `aTouchedFileIsHashedOnceAndItsRowTakesTheNewMtime`, `aRowWhoseStoredSizeDriftedTakesTheFilesSize`, `aMissingDocumentsFileBackWithItsOldStatIsLiveAgain`; the simulation's touch operation and row-stat invariant |
| `DocLifecycleImpl.fileAt`: a live holder's stored stat spares the file a read, a hidden one's does not | `PermanentDeleteTest`: `deletingForGoodReadsNoFileAnotherDocumentHoldsAtTheOldPath` (its files now the same size), `deletingForGoodRemovesItsBytesOverAnotherDocumentsFileAtTheOldPath`, `deletingForGoodTakesNoHiddenRowsStatAsProofOfTheFileAtTheOldPath` |
| `ImportService.recordReturn`: a recorded return is `broughtBack`, a new document is not | `ImportTakeBackTest`: `anImportTheJournalRecordsAsAReturnIsNotUndone`, `anImportOfNewBytesIsNoReturnAndIsUndone` |
| `ImportService.takeBack`: delete only the import's own moved copy | `ImportTakeBackTest`: `anImportThatFoundTheBytesInPlaceLeavesThatFileWhenItsReturnCannotBeRecorded`, `anImportsCopyWrittenOverBeforeItIsTakenBackStays` |
| `JournalCoordinator.recordRestores` (now `FoldLifecycle.settledReturns`): the settle edge | `JournalCoordinatorWritesTest.aReturnIsRecordedExactlyWhenTheSettleWindowHasPassed` |
| `MetadataAccumulator.stage`: skip an applied source whose fingerprint is unchanged | Equivalent: the stat gate before it already returns for every case it could match, so the check is gone. `MetadataAccumulatorTest.aTouchedObjectLosesNothingAndIsNotReadAgain` pins what a touched object does |
| `JournalCoordinator.withDurableProjectionRecovery` / `blockWrites` (now `JournalCoordinator.pass` / `PassOutcome.failed`): the pass's own block, kept | `JournalCoordinatorWritesTest.aWriteAfterAFailedIngestWhoseRebuildWasCutShortIsRefusedForTheIngestsReason` |
| `JournalCoordinator.recordWritten`: the write clock moves past a local write | `JournalCoordinatorWritesTest.aLocalWriteMovesTheStoredWriteClockPastIt` |
| `HlcWatermarkAllocator.observe`: the receive rule's counter | `HlcTest` (`observeAtTheSamePhysicalTime...`, `observeOfARemote...`), `HlcAllocatorPropertyTest` |
| `MetadataAccumulator.runReconcilePass`: an older `PROJECTION_VERSION` is wiped and rebuilt | `MetadataAccumulatorTest.aStoredProjectionOfAnOlderVersionIsRebuiltAtTheNextPass` |

Three runners-up are covered too: `ImportService.unplace` putting a returning document's row back
(`PathIdentityTest.aReturningImportWhoseRowCannotBeWrittenPutsTheHiddenRowBack`),
`DocLifecycleImpl.relocate`'s same-file check
(`DocumentRenameTest.renameOntoTheDocumentsOwnFileUnderAnotherNameIsUnchanged`), and
`MetadataAccumulator.reconcile`'s incremental flag on a pass that read nothing, which its one
caller does not tell apart (pinned in the projection-version test).

## Reading a report

- **Killed**: a covering test failed. **Timed out**: the mutant made a test hang (for example a
  removed loop exit); PIT counts it as detected. **Survived**: every covering test passed. **No
  coverage**: no test ran the line at all; kover shows these too.
- **Mutation score** = (killed + timed out) / all mutants. **Test strength** = the same over the
  mutants that some test covers; it says how good the tests are where they exist, while the score
  also counts the code no test reaches.
- `index.html` lists packages, then classes; a class page shows the source with each line's
  mutants. Green lines had every mutant killed, red lines have a survivor; hover a line's number of
  mutants for what each one changed and which test killed it.
- Some survivors are not test gaps:
  - **Equivalent mutants** change nothing observable. A `>` becoming `>=` between two HLCs is one:
    no two events share an HLC. A boundary change on a size check whose other side cannot occur is
    another.
  - **Inlined code**: a mutant on a line number past the end of its file is in an `inline`
    function's body (from the standard library or from this code base) as compiled into this
    class. The report cannot show its source; the method name says where it was inlined.
  - **Defensive code**: a `require`/`check` whose failing case no caller can reach.
  - **Logging and diagnostics**: a removed log call, or a message string replaced with `""`.
  - **`Unit` returns of suspend functions**: "replaced return value with null" on a suspend
    function that returns `Unit` (compiled to return `Object`) changes nothing a caller reads.
    About a quarter of the survivors in the hotspot classes are this kind.
- The ones worth a test are where a survivor means wrong data could be written or kept: a
  comparison of hashes, sizes or HLCs, a settle or staleness check, a lock or ordering branch, a
  "skip when already applied" short-circuit, a guard before a move or delete.

## When to run it

- After changing tests for the index, session or lifecycle code: did the survivors in that class
  go down?
- Before trusting a refactor of a class whose survivors you know: the survivors are what the
  suite would let a refactor change.
- To compare with the baseline, keep a copy of `mutations.xml`; PIT's numbers are per run, so
  compare counts per class, not just the total.
