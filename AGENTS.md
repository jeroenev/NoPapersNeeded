# AGENTS.md

Kotlin/Compose Multiplatform paperless document manager (Android + Linux desktop).
README covers the design, user-facing build/packaging details, and the library folder layout.

## Modules

- `core` — platform-agnostic domain: journal, index, fs, pdf, ocr, xmp, Room db.
  Room 3 via KSP; schemas in `core/schemas`.
- `composeApp` — shared Compose UI + viewmodels (`dev.npn.app.ui.*`, `di`).
- `androidApp` — Android entrypoint (`dev.npn`, launcher `dev.npn/.MainActivity`).
- `desktopApp` — desktop entrypoint + packaging (deb/AppImage/uber-jar).
- KMP JVM target is named `desktop` (not `jvm`); source sets are
  `desktopMain`/`desktopTest`, tasks are `:core:desktopTest`, `:composeApp:compileKotlinDesktop`, etc.

## Commands

mise wraps Gradle (`mise tasks` lists all; see `mise.toml` + `.mise/tasks/`). Key ones:

- Tests: `./gradlew :core:desktopTest` and `./gradlew :composeApp:desktopTest`
  (viewmodel tests). There is no `:composeApp:desktopTest` mise alias; `mise test` only runs core.
- Single test class: `./gradlew :core:desktopTest --tests 'dev.npn.core.journal.FoldTest'`.
- Lint: `./gradlew detekt` (root task, all modules).
- Fast UI iteration: `mise hot` (Compose Hot Reload on desktop). Android-only paths
  (scanner, share intents, WorkManager) need a device: `mise deploy`.
- CI (`.github/workflows/ci.yml`) runs: detekt → core tests → composeApp tests →
  compile android+desktop → assembleDebug → desktopApp build → packageAppImage.
  Match that order when verifying larger changes.

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
  journal writes must go through `JournalCoordinator`.
- Document identity = SHA-256 of bytes; duplicates are forbidden (one canonical path per hash).
- Merge semantics: OR-set for tags, HLC last-writer-wins for single-value properties.

## Conventions

- Commit messages: `[main] Imperative summary` (branch name in brackets).
- Shared Flow subscriptions use the named `WhileSubscribed(5_000)` constant instead of
  inline timeouts.
- Use `material-icons-extended` for semantically correct icons (already a dependency).
