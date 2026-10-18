# AGENTS.md

Kotlin/Compose Multiplatform paperless document manager (Android + Linux desktop).
README is the end-user promo page; keep technical detail out of it. DEVELOPMENT.md covers the
design, build/packaging/release details, and the library folder layout.

## Modules

- `core` — platform-agnostic domain: journal, index, fs, pdf, ocr, xmp, Room db.
  Room 3 via KSP, two databases per library: the index (`NpnDatabase`, `index.db`) and the
  device's own state (`DeviceDatabase`, `device.db`); schemas in `core/schemas/<database>/`.
- `core-testing` — fakes of `core`'s facets shared by the commonTest of `core` and `composeApp`
  (`dev.npn.core.testing`). Only test source sets depend on it; a fake both suites need
  goes here rather than into either module's tests.
- `composeApp` — shared Compose UI + viewmodels (`dev.npn.app.ui.*`, `di`, and `platform` for the
  `expect` hooks each platform answers).
- `androidApp` — Android entrypoint (`dev.npn`, launcher `dev.npn/.MainActivity`). The debug
  build is a separate package, `dev.npn.debug`, so it installs next to the release build.
- `desktopApp` — desktop entrypoint + packaging (deb/rpm/AppImage/uber-jar; Flatpak manifest
  in `desktopApp/flatpak/`, built by `mise package-flatpak`; `mise flathub-manifest` derives the
  Flathub one). The Flatpak app ID is `io.github.jeroenev.NoPapersNeeded`, which Flathub ties
  to the GitHub repo `jeroenev/NoPapersNeeded`; the Android package stays `dev.npn`.
- Java: one JDK, 25 (`jdk` in `libs.versions.toml`), runs Gradle, compiles everything and is the
  desktop runtime. `.java-version` (what the workflows install) and the daemon criteria in
  `gradle/gradle-daemon-jvm.properties` repeat it; `:buildSrc:test` fails when they disagree.
  Bytecode stays at `jvm-target` 17, with `-Xjdk-release` on desktop compilations, because
  Android shares it and minSdk 30 lacks newer library APIs. Raising `jdk` never raises the
  target; raising the target is a separate, Android-driven decision. Compiler warnings are
  errors in every compilation (`npn.toolchain`, and buildSrc's own build); fix the warning, or pass
  `-Pnpn.werror=false` locally while experimenting. Tests already opt in to `ExperimentalCoroutinesApi`.
- `buildSrc` — build logic: every task class the build scripts register (packaging, `BuildInfo`,
  `checkLocalization`), and convention plugins that read the catalog: `npn.toolchain` (the JDK and
  bytecode target, applied by every module, plus the SDK levels of `androidApp` and
  `baselineprofile` and every Android module's device-test runner), `npn.kmp-module` (the
  `desktop` and Android library targets and their SDK levels, and the test libraries every
  `commonTest` shares, `core-testing` included, for `core`, `core-testing` and `composeApp`) and
  `npn.detekt` (both detekt runs and their baselines; the root build applies it to every project,
  and its map of typed compilations is where a new module gets its typed run). Kotlin, the
  Android Gradle plugin and detekt are on buildSrc's classpath, so modules apply them by id, never
  with `alias(...)`: Gradle refuses a version for a plugin that is already there.
- `baselineprofile` — generates androidApp's baseline profile on a device (`mise baseline-profile`);
  never runs in CI. The release build is R8-minified: reflective code or new native/reflection-heavy
  dependencies may need rules in `androidApp/proguard-rules.pro`, and need a check on a device
  with `installBenchmarkRelease` (see DEVELOPMENT.md).
- KMP JVM target is named `desktop` (not `jvm`); source sets are
  `desktopMain`/`desktopTest`, tasks are `:core:desktopTest`, `:composeApp:compileKotlinDesktop`, etc.
- `core` and `composeApp` also run tests against the Android target on a host JVM
  (`withHostTest {}`): `commonTest` compiles for both targets, and `androidHostTest` is where
  tests for `androidMain`-only code go. Anything touching real framework classes needs a device
  test instead — the host JVM has `android.jar` stubs, not an implementation.

## Commands

mise wraps Gradle (`mise tasks` lists all; see `mise.toml` + `.mise/tasks/`). Key ones:

- Tests: `./gradlew :core:desktopTest` and `./gradlew :composeApp:desktopTest`
  (viewmodel tests). `mise test-fast` runs both; `mise test` runs only core.
- Same suites on the Android target: `./gradlew :core:testAndroidHostTest
  :composeApp:testAndroidHostTest`. Both are in `check`/`allTests`, so `mise test-all` and
  `mise check` cover them; `mise test-fast` deliberately does not.
- Single test class: `./gradlew :core:desktopTest --tests 'dev.npn.core.journal.OrSetTest'`.
- Build logic tests (`buildSrc`, e.g. the `checkLocalization` and `checkFieldTypeBranches`
  guards): `./gradlew :buildSrc:test`. They are not part of `check`; CI runs them with the other
  tests.
- Desktop shell tests (`desktopApp`, e.g. the single-instance lock): `./gradlew :desktopApp:test`.
  CI runs them with the other tests too.
- Live AI endpoint checks are opt-in and skipped by default (they cost money):
  `NPN_LIVE_AI=1 ./gradlew :core:desktopTest --tests 'dev.npn.core.ai.LiveEndpointProbeTest'`.
  Never add a live-endpoint call to a normal test — CI has no credentials.
- Formatting and lint: `./gradlew spotlessCheck detekt`, then `./gradlew detektTyped` (see
  Detekt below; it compiles first, so keep it a separate invocation from spotless).
- Android lint: `./gradlew :androidApp:lintDebug` lints the app and, through `checkDependencies`,
  core's and composeApp's Android code. Errors fail it; `androidApp/lint-baseline.xml`
  grandfathers old findings (never add to it) and `config/lint/lint.xml` is the one configuration.
- Fast UI iteration: `mise hot` (Compose Hot Reload on desktop). Android-only paths
  (scanner, share intents, WorkManager) need a device: `mise deploy`.
- CI (`.github/workflows/ci.yml`) runs: shellcheck (hooks, mise tasks, `.github/scripts`) →
  spotlessCheck → checkLocalization + checkFieldTypeBranches → detekt → tests+coverage (kover
  drives both desktop test suites) → Android-target host tests → compile android+desktop +
  `compileDeviceTests` (the root task that builds every device test without a device; add a new
  module's there) + `detektTyped` + `:androidApp:lintDebug` → committed Room schema exports → assembleDebug → desktopApp
  build → packageAppImage. Match that order when verifying larger changes. Actions
  minutes and artifact storage are metered (private repo): PRs stop after compile, packaging runs
  on `main` only, the coverage report uploads only on `main` or after a failure, binaries upload
  only on a manual run, and the Windows build (`windows.yml`) and CodeQL run weekly or on demand.
  Keep it that way. `mise release` refuses unless CI passed on the `main` commit it bumps; the
  tagged commit changes only `npn.version`, and `release.yml` builds it without waiting for CI.
  Its draft job builds nothing unless CI passed on the tagged commit, or the tag only bumps
  `npn.version` on top of a `main` commit CI passed on (`.github/scripts/check-release-commit.sh`).
  Both count only a `push` or `workflow_dispatch` run as CI on a commit, through the one
  `ci_status` they source (`.github/scripts/ci-status.sh`): a `pull_request` run
  tested the pull request merged into its base branch, not the commit itself.
- Version: `npn.version` in `gradle.properties` is the only version; everything else derives
  from it (`buildSrc` `NpnVersion`, generated `dev.npn.app.BuildInfo`). Never hardcode a version
  or bump it by hand: `mise release <version>` bumps, commits and tags together, and
  `release.yml` rejects a tag that does not match.

## Detekt

- One config, `config/detekt/detekt.yml`, two runs; `check` runs both:
  - `./gradlew detekt`: source-only (no type resolution) over every source set. Fast, needs no
    compile. Baseline: per-module `detekt-baseline.xml`, where present.
  - `./gradlew detektTyped`: the type-resolved per-compilation tasks (`detektMainDesktop`,
    `detektMainAndroid`, `detektTestDesktop`, `detektHostTestAndroid` in `core`/`composeApp`;
    `detektMainDesktop` in `core-testing`; `detektDebug` in `androidApp`; `detektMain` in
    `desktopApp`). Only these run the rules that need the Analysis API (`LongParameterList`,
    `UnusedPrivate*`, `UnusedVariable`, `SuspendFunSwallowedCancellation`, ...); the source-only
    run skips them silently. They also load the Compose ruleset (`io.nlopez.compose.rules:detekt`,
    its default config): viewmodels forwarded into child composables, composables without a
    `modifier`, parameter order and naming. Baseline: per-module `detekt-baseline-typed.xml`, where present.
    Device tests, release variants and `baselineprofile` only get the source-only run.
- A rule that exempts tests lists `*testSources` (this build's test source sets) as its
  `excludes`; detekt's own defaults name other builds' source sets and miss `desktopTest` and
  the Android ones.
- Baselines grandfather old findings; CI fails on NEW issues only. Do not add to baselines; fix
  findings in code you touch. Baselines should shrink: after a cleanup, regenerate with
  `./gradlew :<module>:detektBaseline` or `./gradlew detektTypedBaseline` and check that the diff
  only removes lines.
- The typed analysis runs without compiler plugins (kotlinx.serialization, Compose), so it logs
  "compiler errors found during analysis" and can misreport code next to `serializer()` calls or
  `actual` declarations. Suppress a new false positive with a one-line reason that says so;
  don't contort the code around it.
- A complexity `@Suppress` (`LongMethod`, `LongParameterList`, `TooManyFunctions`,
  `CyclomaticComplexMethod`, ...) counts as baseline growth: a new one needs a one-line reason
  next to it and is called out in the PR. Prefer the real fix.
- `LongMethod` ignores `@Composable` functions: their length measures UI-tree depth, not
  complexity.
- `ForbiddenComment` rejects `TODO:`, `FIXME` and `STOPSHIP:`: track unfinished work as an issue.
- Typed `SuspendFunInFinallySection` (main sources): a `finally` that suspends does its cleanup in
  `withContext(NonCancellable)`, or a cancelled coroutine skips it. Where the enclosing block or the
  callee already is NonCancellable, suppress the hit with that reason.
- Typed potential-bug rules beyond the defaults: `UnconditionalJumpStatementInLoop`,
  `DontDowncastCollectionTypes`, `CharArrayToStringCall`, `ExitOutsideMain`,
  `UnnecessaryNotNullCheck` (the config lists the ones tried and left off, with why).

## Architecture invariants (do not break)

- Library folder is the source of truth; SQLite/Room index is a disposable, rebuildable
  cache stored outside the library folder and never synced. Its builders fall back to a
  destructive migration, and a dropped index rebuilds from the folder on the next open.
- What only this device knows, which nothing could rebuild (journal device id, OCR and AI
  enrichment queues, queued AI edit runs, enrichment marks, assistant chats), lives in
  `device.db` (`DeviceDatabase`) beside the index: never synced, never dropped, real migrations
  only. `DeviceDatabaseSchemaTest` pins each shipped version's identity hash (append only, never
  edit an entry) and opens every shipped schema through the production builder, and CI fails when
  the compiled schema export differs from `core/schemas`. Library data (settings, saved views,
  rules) belongs in the journal, not in either database.
- PDFs are immutable after import (rename/move only). Sole exception: explicit
  bake-in-place, which replaces only the bytes it read and hashed as the document's (while the
  file keeps the size and mtime it had then) and records a `relink` journal event for the new hash.
- All app-written files in `.npn/` are write-once. Journal transactions are content-addressed
  by their complete-file SHA-256 and require verified create-without-replacement publication;
  OCR sidecars are keyed by hash and writer (device id + engine). Never append to or rewrite a
  published object. New journal writes must go through `JournalCoordinator` (`core.index`) — this is enforced
  at the type level, not just by convention: `ContentAddressedJournalWriter` is `internal` to
  `core` and the coordinator's `writer` is private, so no outside caller can grab a raw writer.
- The journal is written only as storage-v2 objects; legacy v1 segments are read, never written.
  A filesystem that cannot publish without replacement (no hard link, no exclusive rename) refuses
  to open the library, at every open (`DeviceIdentity` publishes before anything is written); never
  add a fallback format. `device.id` belongs to one installation:
  `DeviceIdentity` gives a restored or cloned installation a fresh one (ARCHITECTURE.md,
  "Journal storage").
- Older builds refuse a journal object holding an op they do not know (a new system property or
  field type counts): they block writes and stop opening the library. Avoid new ops; an unavoidable
  one ships in a build that reads it before any build writes it, and its commit says so.
- Document identity = SHA-256 of bytes; duplicates are forbidden (one canonical path per hash).
  A path holds one row: a trashed, deleted or missing row gives it up to a live document, flags
  kept (`DocumentDao.upsertAll`; for a rename or move onto it, `DocumentPathReconciler`). A hidden
  document is back only when the file at its path hashes to it; something else at its old name,
  or a file still being written, is no evidence. An import is a duplicate only of a live document
  whose file holds the bytes (the size and mtime its row stored prove that, as for a rescan; a
  drifted stat means hashing the file); a hidden document's bytes bring it back (`ImportService`),
  since a caller may delete a duplicate's source. Bytes a relink superseded (a bake's original)
  bring nothing back, as no event makes a superseded document live: an import of them is a
  duplicate of the document they became while the library shows it, else refused, and a rescan
  gives a file of them no row. A file is moved, trashed or deleted as a document's only while it
  holds the document's bytes by that same proof (`holdsDocument`) or a hash; another file at the
  document's path is left for the next rescan.
- Merge semantics: OR-set for tags, HLC last-writer-wins for single-value fields, system
  properties, settings and field definitions, and for trash/restore and delete/undelete.
- Document fields are registry data, not code. Title, correspondent, docType, docDate and notes
  are ordinary `field-set` fields whose definitions the library seeds; there is no per-field
  Kotlin. That includes what a model is told: a field's type, its `perDocument` flag (the title's
  value is its own, so no vocabulary is shared and it is written in the app's language) and its
  hint, which carries the seeded fields' guidance. A change to the seeds raises
  `FieldKeys.SEED_VERSION`; `FieldKeys.upgradeSeeded` then brings libraries seeded earlier up to
  it once, never overriding what their users changed. An exhaustive `when (FieldType)` belongs
  only in `FieldValues` (canonical form) and in the UI, where the type picks a control:
  `FieldEditor.kt` (the editor and its type label) and the filter sheet's per-type filter
  control. Every other branch reads the `FieldDef`'s data (its flags and hint, and the
  capabilities each `FieldType` constant must declare, such as `singleLine` or `hasVocabulary`),
  never compares or switches on the type; `./gradlew checkFieldTypeBranches` (in `check`, CI and
  the pre-commit hook) fails on a `FieldType.X ->` branch, an `==`/`!=` against a constant or an
  `is FieldType` in main sources outside those files (`build.gradle.kts` lists them). Storage is always the canonical form; a blank value
  clears the field and is never stored as `""`. Each number has exactly one canonical form (`7`,
  `1.5`), so canonical strings compare as values; the fold reads what older builds stored (`007`,
  `1.50`) through `FieldValues.normalized`. The five typed `documents` columns are a read cache
  the fold writes from the field map, never a second source.

## Conventions

- Read ARCHITECTURE.md for the module/package map, data flow, and where to look
  for common tasks before navigating the codebase.
- Commit messages: `[main] Imperative summary` (branch name in brackets).
- Shared Flow subscriptions use `WhileSubscribedTimeout` (`WhileSubscribed(5_000)`, in
  `composeApp`'s `ui/common/StateFlowConfig.kt`) instead of inline timeouts; typed detekt forbids
  `SharingStarted.WhileSubscribed` elsewhere.
- Wall-clock time comes from an injected `EpochClock` (`SystemEpochClock` in production); outside
  tests, typed detekt forbids `System.currentTimeMillis`, `Instant.now` and `Clock.System.now`.
- Use `material-icons-extended` for semantically correct icons (already a dependency).
- Around suspend code use `runCatchingCancellable` (`core.util`), never `runCatching` or a bare
  `catch (Throwable)`: cancellation must propagate, not be recorded as a failure. Typed detekt's
  `SuspendFunSwallowedCancellation` enforces it. Error text is `Throwable.reason()`.
- Background work nobody awaits reports failures through `NpnLog` (`SessionDeps.log`) with
  `log.runLogged("what") { … }`, never `println` or a silent `runCatching`. Outside tests, typed
  detekt forbids `print`/`println`, `runBlocking`, `Thread.sleep` (`ForbiddenMethodCall`) and
  `GlobalScope` (`GlobalCoroutineUsage`).
- A viewmodel reports to the user through `MessagesViewModel`: `message(text, action)`, and every
  write through `launchReporting(failed)` (or `reporting` inside a coroutine it launched), where
  `failed` is the whole failure sentence with the reason as `%1$s`. Never raise a snackbar from a
  composable. New text is `UiText.Res`/`UiText.Plural` over a whole-sentence resource in all six
  locales, never a sentence stitched from fragments; `UiText.Raw` is only for untranslated text
  such as a file name or an exception's message. Viewmodel tests assert on the `UiText` itself.
- UI is built from the shared pieces in `ui/common` (`SettingsSection`, `SwitchRow`, `HelpText`,
  `Dropdown`, `ExpanderRow`, `SuggestField`, `BackTopBar`, `TooltipIconButton`, ...) rather than a
  screen's own copy, and a screen binds keys only through `keyShortcuts`/`previewKeyShortcuts`.
  Leaf composables take state and an actions interface, never a viewmodel; wide-or-compact comes
  from `LocalWindowWidthClass`, never a screen's own window-width check.
- Facets follow the contract in ARCHITECTURE.md: main-safe, expected failures as a typed outcome
  with an error enum (mapped to a string resource in `composeApp`, never English from `core`),
  throws only for bugs and misuse.
- JSON goes through `NpnJson.Persisted` (what the app writes and reads back) or `NpnJson.Lenient`
  (model output, third-party responses), never a new `Json {}`. Only `JournalCodec` keeps its own;
  typed detekt forbids the `Json {}` builder everywhere else.
