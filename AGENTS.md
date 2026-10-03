# AGENTS.md

Kotlin/Compose Multiplatform paperless document manager (Android + Linux desktop).
README covers the design, user-facing build/packaging details, and the library folder layout.

## Modules

- `core` — platform-agnostic domain: journal, index, fs, pdf, ocr, xmp, Room db.
  Room 3 via KSP; schemas in `core/schemas`.
- `composeApp` — shared Compose UI + viewmodels (`dev.npn.app.ui.*`, `di`).
- `androidApp` — Android entrypoint (`dev.npn`, launcher `dev.npn/.MainActivity`). The debug
  build is a separate package, `dev.npn.debug`, so it installs next to the release build.
- `desktopApp` — desktop entrypoint + packaging (deb/rpm/AppImage/uber-jar; Flatpak manifest
  in `desktopApp/flatpak/`, built by `mise package-flatpak`; `mise flathub-manifest` derives the
  Flathub one). The Flatpak app ID is `io.github.jeroenev.NoPapersNeeded`, which Flathub ties
  to the GitHub repo `jeroenev/NoPapersNeeded`; the Android package stays `dev.npn`.
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
- Single test class: `./gradlew :core:desktopTest --tests 'dev.npn.core.journal.FoldTest'`.
- Live AI endpoint checks are opt-in and skipped by default (they cost money):
  `NPN_LIVE_AI=1 ./gradlew :core:desktopTest --tests 'dev.npn.core.ai.LiveEndpointProbeTest'`.
  Never add a live-endpoint call to a normal test — CI has no credentials.
- Formatting and lint: `./gradlew spotlessCheck detekt`.
- Fast UI iteration: `mise hot` (Compose Hot Reload on desktop). Android-only paths
  (scanner, share intents, WorkManager) need a device: `mise deploy`.
- CI (`.github/workflows/ci.yml`) runs: spotlessCheck → shellcheck → checkLocalization →
  detekt → tests+coverage (kover drives both desktop test suites) → Android-target host tests →
  compile android+desktop → assembleDebug → desktopApp build → packageAppImage. Match that order
  when verifying larger changes. Actions minutes and artifact storage are metered (private repo):
  PRs stop after compile, packaging runs on `main` only, binaries upload only on a manual run, and
  the Windows build (`windows.yml`) and CodeQL run weekly or on demand. Keep it that way.
- Version: `npn.version` in `gradle.properties` is the only version; everything else derives
  from it (`buildSrc` `NpnVersion`, generated `dev.npn.app.BuildInfo`). Never hardcode a version
  or bump it by hand: `mise release <version>` bumps, commits and tags together, and
  `release.yml` rejects a tag that does not match.

## Detekt

- Source-only (no type resolution), config at `config/detekt/detekt.yml`.
- Per-module `detekt-baseline.xml` grandfathers old findings; CI fails on NEW issues only.
  Do not add to baselines; fix findings in code you touch. Baselines should shrink.

## Architecture invariants (do not break)

- Library folder is the source of truth; SQLite/Room index is a disposable, rebuildable
  cache stored outside the library folder and never synced.
- PDFs are immutable after import (rename/move only). Sole exception: explicit
  bake-in-place, which records a `relink` journal event for the new hash.
- All app-written files in `.npn/` are write-once. Journal transactions are content-addressed
  by their complete-file SHA-256 and require verified create-without-replacement publication;
  OCR sidecars are keyed by hash+deviceId. Never append to or rewrite a published object. New
  journal writes must go through `JournalCoordinator` — this is enforced at the type level, not
  just by convention: `SegmentWriter`/`ContentAddressedJournalWriter` and the coordinator's
  `writer` are `internal` to `core`, so no outside caller can grab a raw writer.
- Document identity = SHA-256 of bytes; duplicates are forbidden (one canonical path per hash).
- Merge semantics: OR-set for tags, HLC last-writer-wins for single-value fields, system
  properties, settings and field definitions.
- Document fields are registry data, not code. Title, correspondent, docType, docDate and notes
  are ordinary `field-set` fields whose definitions the library seeds; there is no per-field
  Kotlin. An exhaustive `when (FieldType)` belongs only in `FieldValues` (canonical form) and in
  the UI, where the type picks a control: `FieldEditor.kt` (the editor and its type label) and the
  filter sheet's per-type filter control. Every other branch reads the `FieldDef`'s data (type,
  flags, hint), never switches on the type. Storage is always the canonical form; a blank value
  clears the field and is never stored as `""`.
  The five typed `documents` columns are a read cache the fold writes from the field map, never
  a second source.

## Conventions

- Read ARCHITECTURE.md for the module/package map, data flow, and where to look
  for common tasks before navigating the codebase.
- Commit messages: `[main] Imperative summary` (branch name in brackets).
- Shared Flow subscriptions use the named `WhileSubscribed(5_000)` constant instead of
  inline timeouts.
- Use `material-icons-extended` for semantically correct icons (already a dependency).
