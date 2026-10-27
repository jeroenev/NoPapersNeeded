# Mutation testing

Line coverage (kover) says a line ran. It does not say a test would notice the line being wrong.
Mutation testing measures that: [PIT](https://pitest.org) makes one small change to the compiled
code at a time (a negated or removed condition, a changed boundary, a removed call, a constant
return), runs the tests that cover the changed line, and records whether any of them failed. A
change no test notices is a *surviving mutant*: a place where the code could be wrong and the suite
would still pass.

It runs over `core`'s `desktop` target, where the session, coordinator, indexer, import and
lifecycle code lives and where most of the review rounds' bug fixes landed. It is slow, so it never
runs in `check`, `allTests` or `ci.yml`: run it on demand, locally or through
`.github/workflows/mutation.yml` (`workflow_dispatch` only), which uploads the HTML report as the
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

## Where the survivors are

Most survivors sit in a few large classes, which are the ones worth watching when their code or
tests change: in `core.index`, `JournalCoordinator`, `ImportService`, `Indexer`, `FoldProjection`
and `MetadataAccumulator`; in `core.app`, `LibrarySessionImpl` (the lowest score of the large
classes), `MetadataWriterImpl`, `DocLifecycleImpl` and `DocContentFacadeImpl`; in `core.journal`,
`FoldedState`, `EventDecoderRegistry` and `JournalEventSerializer`. `core.app` also has the most
mutants without coverage, so its score sits below its strength.

A complete run of both default packages takes about an hour at four threads on a 16-core laptop;
run it alone. `mutation.yml` runs it at two threads in a 165-minute step, which may be too short on
a two-core hosted runner, and PIT writes its report only at the end, so a run cut short shows
nothing. That is why the workflow has no schedule: dispatch it, preferably over one package
(`dev.npn.core.index`) at a time.

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
    A good share of the survivors in the large classes are this kind.
- The ones worth a test are where a survivor means wrong data could be written or kept: a
  comparison of hashes, sizes or HLCs, a settle or staleness check, a lock or ordering branch, a
  "skip when already applied" short-circuit, a guard before a move or delete.

## When to run it

- After changing tests for the index, session or lifecycle code: did the survivors in that class
  go down?
- Before trusting a refactor of a class whose survivors you know: the survivors are what the
  suite would let a refactor change.
- To compare with an earlier run, keep a copy of its `mutations.xml` (or its report): nothing
  compares runs automatically. PIT's numbers are per run, so compare counts per class, not just the
  total.
