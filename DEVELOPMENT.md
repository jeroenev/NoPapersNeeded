# Development

Design, building, packaging, releases and the library folder format. For the
module/package map and data flow, read [ARCHITECTURE.md](ARCHITECTURE.md); for
conventions and invariants, [AGENTS.md](AGENTS.md). The user-facing overview is in
[README.md](README.md).

## Design

- Files are the source of truth. The library is a folder of PDFs plus a small
  `.npn/` metadata directory. Any device with the folder has everything, and the
  app can rebuild all of its state from the folder alone.
- Documents are immutable. Once a PDF is in the library the app never rewrites its
  bytes (rename/move only). The one exception is the explicit bake-in-place action.
- Metadata is a journal. Changes (tags, fields, properties, library settings, trash,
  relink) are stored as immutable JSONL transactions under `.npn/journal/`. Transactions
  use their complete-file SHA-256 as the filename. No published transaction is modified
  after it is written. Merges are conflict-free: OR-set semantics for tags,
  hybrid-logical-clock last-writer-wins for single-value fields, properties and settings.
- SQLite is a rebuildable cache. The index (search, source provenance and incremental
  metadata projection) and the thumbnail cache live in device-local storage,
  never inside the library folder, and are never synced. Delete them and they rebuild from
  the folder.
  What only this device knows (its device id, its OCR and AI queues, AI edit
  runs awaiting review, assistant chats) sits beside it in a separate `device.db`,
  which is never dropped. A `device.db` restored or copied onto another installation
  keeps all of that but takes a new device id, so two installations never write
  under one name.
- OCR travels as sidecars. Extracted text is written to `.npn/ocr/` keyed by file
  hash, device id and engine, so a device adopts another device's OCR instead of
  redoing it.
  The best-ranked engine is indexed (ai > mlkit > tesseract > embedded text layer);
  a device runs its own engine only when it would rank higher, or when the user asks
  for a re-OCR and its engine ranks at least as high. A re-run of an engine the device
  already published gets a run-stamped name (`<deviceId>.r<millis>.<engine>`), and
  the newest run of an engine wins.
- Identity is the SHA-256 of the file bytes. Renames and moves never detach
  metadata, and imports dedup by hash.
- XMP is baked on export. Exported/shared copies get `dc:subject` keywords and an
  `npn:` namespace embedded for interoperability with other tools; the library file
  is untouched unless you explicitly bake in place.

## Technical features

- Import and scan: MLKit document scanner on Android, file dialog and drag-and-drop
  on desktop, plus an optional watched inbox folder.
- Share-sheet import on Android (PDFs and images; images are converted to PDF).
- Full-text search over filenames, content and tags using SQLite FTS5.
- Background OCR: MLKit on Android, Tesseract CLI on desktop. With an AI endpoint
  configured, a vision model can read a page instead, per document or as the device's
  default engine. It costs one request per page, so it is never automatic unless the
  user turns it on.

## Requirements

- Android 11+ (API 30).
- Desktop: Java 17 or newer. Packaged distributions bundle their own Java 25 runtime, so
  no system JVM is required to run them.
- Library storage that can create a file without replacing one another writer just created:
  hard links, or an exclusive rename (`renameat2` on Linux and Android, `MoveFileExW` on
  Windows), or Android's storage access framework. A library on storage with neither is
  refused at every open, also when it was remounted since the last one.
- OCR on desktop: the Windows installers and the Flatpak bundle Tesseract with models for
  every UI language. The other Linux packages need `tesseract-ocr` on `PATH`; without it,
  only embedded PDF text is indexed, and scans imported meanwhile are OCR'd once it is there.
- Cloud sync uses a bundled rclone. Building the Android app cross-compiles it into the APK,
  which needs Go 1.26+ on `PATH` and Android NDK 29 (`sdkmanager --install "ndk;29.0.14206865"`);
  `-Pnpn.rclone.skip=true` builds without the engine for fast iteration, never for a release.
  Desktop packaging downloads the pinned rclone release (`:desktopApp:downloadRclone`) into the
  app image. The uber-jar carries no app resources, so it looks for `rclone` on `PATH`.
  The APK carries the engine for `arm64-v8a` only (the phones the app targets); an x86_64 emulator
  needs `-Pnpn.rclone.abis=x86_64` (or a list; `mise deploy` passes the device's own ABI), and `useLegacyPackaging` keeps it extractable, as
  Android runs it from the installed native library directory.

## Building

The build needs one JDK, Java 25 (`jdk` in `gradle/libs.versions.toml`). It runs the
Gradle daemon (pinned in `gradle/gradle-daemon-jvm.properties`), compiles every module and
is bundled as the desktop app's runtime; the workflows install it from `.java-version`. A
`buildSrc` test fails when either of those two files names another JDK than the catalog.
Gradle downloads it via the foojay resolver if no matching JDK is installed. All code still
compiles to Java 17 bytecode (`jvm-target`): Android only provides newer Java library APIs on
newer OS versions, and the app supports API 30. Desktop compilations also pass
`-Xjdk-release=17`, so they cannot call a newer API either. Both levels are applied in one
place, the `npn.toolchain` convention plugin in `buildSrc`, which every module applies and
which also sets the Android SDK levels of `androidApp` and `baselineprofile`; the
multiplatform modules (`core`, `core-testing`, `composeApp`) also share their `desktop` and
Android targets, SDK levels included, through `npn.kmp-module`. Both plugins read `gradle/libs.versions.toml`. The
Android SDK is located via `local.properties` (`sdk.dir`) or the `ANDROID_HOME` /
`ANDROID_SDK_ROOT` environment variable.

Every Kotlin compilation, `buildSrc` included, treats compiler warnings as errors and runs in
progressive mode (`npn.toolchain`). To try something that warns, such as a dependency upgrade
with new deprecations, pass `-Pnpn.werror=false` (or set it in `~/.gradle/gradle.properties`);
CI always builds with warnings as errors. Test source sets of the multiplatform modules opt in to
`kotlinx.coroutines.ExperimentalCoroutinesApi`, so tests need no `@OptIn` for the test dispatchers.

### Task runner (mise)

Common build, run, test, and device-deploy commands are wrapped as
[mise](https://mise.jdx.dev) tasks in `mise.toml` (plus file tasks in
`.mise/tasks/`). Run `mise tasks` to list them with what each does, and
`mise <task>` to run one; most are a single Gradle command you can also run directly.
The ones that need more than their description:

- `mise deploy` installs the debug build over adb and launches it (`--release` for the
  release build); `mise pair` is a one-time adb Wi-Fi pairing wizard (Android 11+), and
  `mise connect <ip:port>` reconnects a paired device. `mise emulator` starts an Android
  emulator in its place (see [Android emulator](#android-emulator)).
- `mise test-device` runs the instrumentation tests on a connected device; CI only compiles them.
- `mise baseline-profile` regenerates the baseline profile on a device on Android 13+ (see
  [Release builds: R8 and baseline profiles](#release-builds-r8-and-baseline-profiles)).
- `mise rclone-android` cross-compiles just the bundled rclone for Android, which `mise apk`
  and every other Android build do on the way (see [Requirements](#requirements)).
- `mise apk-release` is signed only with `NPN_KEYSTORE_*` set, and `mise release <version>`
  bumps the version, tags `v<version>` and pushes; CI publishes the release (see
  [Releases](#releases)).
- `mise package-flatpak` needs `flatpak-builder` and `elfutils` (its `eu-strip`);
  `mise flathub-manifest [version]` derives the
  Flathub manifest for a published release.
- `mise hooks-install` points Git at the repository's hooks (`.githooks/`), once per clone.

Formatting is enforced with Spotless and ktlint on every Kotlin source and Gradle Kotlin
script, unused imports included. Run `./gradlew spotlessCheck` to check it or
`./gradlew spotlessApply` to format.
Localization parity and XML placeholders for every `values-<language>` translation,
and obvious hardcoded strings in shared Compose UI, are checked with
`./gradlew checkLocalization` (its own tests: `./gradlew :buildSrc:test`). The task
also runs as part of `./gradlew check`; intentional language-neutral literals must be
listed in `config/localization-hardcoded-allowlist.txt`.
Android lint runs with `./gradlew :androidApp:lintDebug` (CI runs it too). It lints the app and,
through `checkDependencies`, the Android code of `core` and `composeApp`; an error fails the build.
`config/lint/lint.xml` configures it for every module (generated Room code and third-party jars
are left out, and version-update checks are Dependabot's); there is no baseline. The multiplatform
modules also lint their own Android and desktop code as part of `check`.
`./gradlew checkFieldTypeBranches` (also part of `check`) keeps document fields registry data: it
fails on code that branches on a `FieldType` constant outside the few files allowed to (canonical
form and the per-type UI controls, listed in `build.gradle.kts`).
Run `mise hooks-install` once per clone to enable the hooks. The pre-commit hook checks
formatting, source-only detekt, localization and FieldType branches, which takes seconds; it never
formats or stages files. The pre-push hook runs the core and composeApp desktop tests without the
library simulation's seeds (about two minutes after a change to core; `git push --no-verify` skips
it). Commit messages are prefixed with the current branch name when needed.

### Tests

```
./gradlew :core:desktopTest
```

`mise test-fast` also runs the shared UI tests; `mise test-all` and `mise check` add the
Android-target host tests. See [AGENTS.md](AGENTS.md) for the full list.
`mise mutation` measures how much of `core`'s index and app code the tests would notice going wrong
(PIT, on demand; see [docs/MUTATION_TESTING.md](docs/MUTATION_TESTING.md)).

Regression coverage follows the failure modes from the code audit:

- `DocumentLifecycleSequenceTest` runs repeatable generated sequences of import, trash, restore,
  delete, rename, move, metadata edits, disappearance and bake through public session facets.
  An independent model checks two devices after each operation, then checks a new device and an
  index rebuild. A failure prints the seed and complete operation trace.
- `LifecycleRecoveryTest` injects failures before and after publication or a file move, closes the
  session, and checks recovery through another open. `FileSystemFaults` is the storage adapter
  these tests use: it arms one failure at a publication, move or delete (including one that lands
  before it reports failure, or a deletion refused after a tombstone has landed), and can keep
  failing publications or refusing deletions under a path until a test lifts it.
- Gated tests in `BakeInPlaceTest`, `TrashDuringABakeTest`, `BulkTrashTest`,
  `LifecycleBehindARescanTest`, `HiddenDocumentImportTest`, `StoragePathRuleIntegrationTest`,
  `IndexUpgradeTest`, `OcrPipelineRerunTest`, `DocumentDraftsTest` and `AiChatViewModelTest` check
  operations while another operation is suspended at its storage or save boundary (`FsGate` holds
  one at the library filesystem). They assert the result after the gate opens, rather than relying
  on timing alone.
- `LibrarySettingsTest` and `FieldRegistryTest` cover different devices and missing local index
  rows. `IndexUpgradeTest` starts a session over a dropped index, holds its initial rescan, and
  fails that rescan before retrying: OCR requests, paid pages, enrichment and edit jobs survive.
  `SessionStartupCloseTest` closes during that first scan, releases every waiting queue drainer,
  and checks that paid work remains queued.
- `OlderJournalFixtureTest` reads frozen object and older snapshot bytes from
  `core/src/desktopTest/resources/journal-compatibility/`, tests both arrival orders, and rebuilds
  the index after reconciliation. Do not regenerate these fixtures with the current encoders.
  Database migration tests use exported schemas.
- `WatchedFolderImporterTest`, `StoragePathRuleIntegrationTest` and `OcrPipelineNoEngineTest`
  cover a watched folder inside the library, storage without relocation, and installing an OCR
  engine after an earlier run had none. The UI wording tests cover every translated locale for
  seeded hints, scan phases and relink reasons, including text that the localization key guard
  cannot inspect.

The library simulation (`LibrarySimulationTest1` to `LibrarySimulationTest8`, one class per shard
of the seeds) is a deterministic simulation of two to four replicas of one library. It
runs real sessions over in-memory library folders, with a seeded file sync tool between them, on
virtual time, with seeded operations, external file changes, faults and crashes. It checks
invariants after every step, compares each replica's index with a rebuilt one at rest, and after a
full sync checks that every replica shows the same library. CI runs a few seeds; a scheduled
workflow runs more every night. [docs/SIMULATION.md](docs/SIMULATION.md) covers long runs,
replaying a seed, adding an operation or an invariant, and what it does not model yet.

Android's host JVM has framework stubs. Its tests cannot verify a real document provider, an
activity's retained state, URI grants, FileProvider routing or the installed app's network policy.
Run the instrumentation suites on a connected Android device:

```
mise test-device
# Equivalent:
./gradlew :core:connectedAndroidDeviceTest :composeApp:connectedAndroidDeviceTest :androidApp:connectedDebugAndroidTest
```

Set `ANDROID_SERIAL` to one serial from `adb devices` to select a device. This also avoids duplicate
test runs when wireless ADB exposes the same phone through both an IP address and an mDNS name.

The core suite drives a controllable real `DocumentsProvider`, including directory collisions,
stale directory IDs, interrupted copies and a rename that lands before reporting failure.
The Compose suite checks actual activity recreation and library switching, and the system's
persistable-grant refusal. The app suite intercepts the share chooser to check that two shares with
the same name keep separate readable URIs, and contacts only a loopback HTTP server to check the
installed app's network policy. These tests make no paid AI calls. CI compiles all three device
suites and checks for duplicate classes and Java resources without building their APKs; running
them needs a device and remains an explicit local check.

### Deploying to an Android device

To iterate on a physical device without a cable, pair once over Wi-Fi
(Android 11+, phone and workstation on the same network), then deploy with a
single command:

```
mise pair      # one time: follow the prompts, uses the phone's Wireless debugging screen
mise deploy    # every time: build + install over adb + launch the debug build
```

`mise deploy` runs the Android Gradle Plugin `installDebug` task (build + adb
install) and then `adb shell am start -n io.github.jeroenev.NoPapersNeeded.debug/dev.npn.MainActivity`.

The debug build is its own package, `io.github.jeroenev.NoPapersNeeded.debug`, so it installs next to the release
build (`io.github.jeroenev.NoPapersNeeded`) rather than replacing it. It shows up as "NPN debug" with an amber
icon and proposes `Documents/NoPapersNeeded-debug` as its library, so it only writes to
your real library if you point it there; it then acts as a second device on that
library. `mise deploy --release` installs the release build instead, which needs the
`NPN_KEYSTORE_*` variables from [Releases](#releases). If the device
drops off the network later, reconnect with `mise connect <ip:port>` (no
re-pairing needed). A USB-connected device works too; skip `mise pair`.

For fast UI iteration without a device, use `mise hot`: the Compose UI in
`composeApp` runs on the desktop target with Compose Hot Reload, so edits to
shared composables update live on save. Android-only code paths (document
scanner, share intents, WorkManager) are not exercised there; use `mise deploy`
for those.

### Android emulator

The official Android Emulator stands in for a phone for everything but the document scanner.
The ML Kit scanner runs inside Google Play services and reportedly refuses emulated devices, so
test it on a phone. Setup, once per machine (Linux needs KVM: `emulator -accel-check` should
report it usable):

```
SDK=~/Android/Sdk   # or $ANDROID_HOME
$SDK/cmdline-tools/latest/bin/sdkmanager --install "emulator" \
  "system-images;android-37.0;google_apis_playstore;x86_64" \
  "system-images;android-30;google_apis_playstore;x86_64"
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n api37 -d pixel_9 \
  -k "system-images;android-37.0;google_apis_playstore;x86_64"   # targetSdk
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n api30 -d pixel_5 \
  -k "system-images;android-30;google_apis_playstore;x86_64"     # minSdk
```

Pick `google_apis_playstore` images, which carry Play services. In
`~/.android/avd/<name>.avd/config.ini`, `hw.keyboard=yes` lets you type with the host keyboard.
`hw.gpu.enabled=yes` turns on GPU rendering, and a larger `hw.ramSize` (4G) helps the newer image.
On Ubuntu the emulator window needs `libxcb-cursor0`; without it the emulator aborts with "no Qt
platform plugin could be initialized".

Then:

```
mise emulator [avd]              # starts it (default: the first AVD) and waits for boot
mise emulator --headless api37   # no window: agents and sessions without a display
mise deploy                      # installs on it like on a phone
adb emu kill                     # stops it
```

`mise emulator` prints the emulator's serial. With a phone connected too, prefix
`mise deploy` with `ANDROID_SERIAL=<serial>`; otherwise it prefers the phone. The APK carries
the cloud sync engine for `arm64-v8a` only, so `mise deploy` passes `-Pnpn.rclone.abis=<abi>`
when the device reports another ABI, which an x86_64 emulator does (see [Requirements](#requirements)).
A plain `./gradlew installDebug` does not, and cloud sync then fails on the emulator. Switching
between a phone and an emulator rebuilds the engine (about 40 s) and the configuration cache.

### Release builds: R8 and baseline profiles

The release APK is shrunk and optimized by R8 (`isMinifyEnabled` and `isShrinkResources`).
Libraries ship their own keep rules; `androidApp/proguard-rules.pro` holds the few that are
missing, each with the failure it prevents (JNA's native lookups, ML Kit's reflective
component discovery, optional classes that are referenced but never shipped). R8 problems
only show at runtime, so after changing dependencies or reflective code, install
`installBenchmarkRelease` (a minified build with the release rules, see below) and check that a
library opens, a tag can be added, OCR runs and the AI settings can load models.

The APK also ships a baseline profile: `androidApp/src/main/generated/baselineProfiles/`, which
lists the code to compile ahead of time at install, so startup and the first scrolls do not wait
for the JIT. `profileinstaller` applies it on sideloaded installs. The `:baselineprofile` module
generates it by driving the app on a connected device:

```
mise baseline-profile   # Android 13+ device; takes about 10 minutes; leave the phone alone
```

It installs `io.github.jeroenev.NoPapersNeeded.benchmark` (debug-signed, next to any other build), which proposes its own
library folder, `Documents/NoPapersNeeded-benchmark`. The generator seeds demo PDFs there,
grants all-files access through the system settings switch, and records cold start
(`startup-prof.txt`, which also orders the DEX) and browsing (`baseline-prof.txt`). It finds the
app's buttons and tabs by the test tags in composeApp's `TestTags` (exposed on Android as resource
ids), never by their text, so the device can run any language. Gradle uninstalls the app
afterwards; delete the folder if you like. Regenerate the profile and commit it when startup or
the library screens change substantially; a stale profile still works, it just covers less.

### Raw Gradle commands

Android debug APK:

```
./gradlew :androidApp:assembleDebug
# output: androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Desktop, run from source:

```
./gradlew :desktopApp:run
```

Desktop, build a Linux `.deb`:

```
./gradlew :desktopApp:packageDeb
# output: desktopApp/build/compose/binaries/main/deb/no-papers-needed_<version>_amd64.deb
```

This task runs jpackage on the app image the Compose plugin builds, rather than
letting the plugin produce the `.deb`. The reason is the desktop entry: jpackage
generates it from a template that can be overridden with `--resource-dir`, but the
plugin appends its own `--resource-dir` after any `freeArgs`, and jpackage keeps the
last one ([#773](https://github.com/JetBrains/compose-multiplatform/issues/773),
[CMP-9837](https://youtrack.jetbrains.com/projects/CMP/issues/CMP-9837/packageDeb-task-fails-to-include-custom-.desktop-files)).
Without the override the entry has no `StartupWMClass`, so GNOME cannot match the
running window to the installed app and shows a generic taskbar icon. Every Linux package
(`.deb`, `.rpm`, AppImage, Flatpak) renders its desktop entry from the same template,
`desktopApp/linux/no-papers-needed.desktop`, filling in only how it starts the app and names its
icon.

`./gradlew :desktopApp:packageRpm` builds the `.rpm` the same way. It needs `rpmbuild`,
which Debian/Ubuntu hosts get from the `rpm` package.

On Windows, `./gradlew :desktopApp:packageDistributionForCurrentOS` produces `.msi`
and `.exe` installers (via WiX, downloaded automatically at build time, so network
access is needed) and uses `icon.ico`; `windows.yml` builds both on every push to
`main`. They bundle Tesseract: `prepareWindowsTesseract` takes `tesseract.exe` and the
DLLs it links from the pinned UB Mannheim installer, which needs 7-Zip on `PATH` to unpack,
and adds the `tessdata_fast` models the Flatpak ships. Those models are listed once, in
`desktopApp/tessdata-fast.txt`; a desktopApp test checks the Flatpak manifest against it. A
binutils `strip` (MinGW's, on the GitHub runners) removes ~100 MB of debug info from those DLLs.
The Windows workflows run the bundled `tesseract --list-langs` after packaging, so a missing DLL
fails the build.

Desktop, build a Linux AppImage:

```
./gradlew :desktopApp:packageAppImage
# output: desktopApp/build/appimage/no-papers-needed-<version>-x86_64.AppImage
```

jpackage cannot emit AppImage directly, so this task wraps the jpackage app-image
(bundled JRE plus launcher) with `appimagetool`. `appimagetool` is downloaded
automatically into `desktopApp/build/appimagetool/` on first run, so this task needs
network access. It is kept out of `desktopApp/build/appimage/`, since the release uploads every
`.AppImage` and `.zsync` there. It is pinned to a tagged release and its SHA-256
(`appImageToolVersion`/`appImageToolSha256` in `desktopApp/build.gradle.kts`, checked by
`VerifiedDownload` in buildSrc, as the Windows Tesseract downloads are); to bump it, take the
hash GitHub lists for the asset. The AppImage runtime it embeds is pinned the same way
(`appImageRuntimeVersion`/`appImageRuntimeSha256`, a tagged `AppImage/type2-runtime` release,
passed with `--runtime-file`); left to itself, appimagetool downloads the rolling `continuous`
runtime on every build. It builds with `--appimage-extract-and-run` and therefore does not
need FUSE on the build host; running the resulting AppImage still needs FUSE, or the
end user can run it with `--appimage-extract-and-run`. The AppImage is x86_64 only.

Desktop, build a Flatpak bundle:

```
mise package-flatpak
# output: desktopApp/build/flatpak/no-papers-needed-<version>-x86_64.flatpak
```

The manifest, `desktopApp/flatpak/io.github.jeroenev.NoPapersNeeded.yml`, installs the
Linux tarball layout (`./gradlew :desktopApp:packageLinuxTarball`: the jpackage app image plus
the desktop entry, metainfo and icon) on the `org.freedesktop.Platform` runtime; Gradle does
not run inside flatpak-builder. The sandbox cannot run a host `tesseract`, so the manifest
builds Tesseract from source with fast models for every UI language. Only
the first run compiles it: flatpak-builder's state lives in
`~/.cache/no-papers-needed/flatpak/`, outside the project, because its sandbox roots link
into the host's `/run`. The task needs `flatpak-builder`, either the distro package or
`flatpak install flathub org.flatpak.Builder`, and installs the runtime and SDK from Flathub
if they are missing.

Install the bundle with `flatpak install --user no-papers-needed-<version>-x86_64.flatpak`;
it fetches the runtime from Flathub if needed. The sandbox can only read and write
`~/Documents`, so a Flatpak library lives there: the app opens its library by path on every
start, and a folder picked elsewhere only comes back as a document-portal path. Settings and
the index go to `~/.var/app/io.github.jeroenev.NoPapersNeeded/`, so a Flatpak install does
not share them with a `.deb` or AppImage install on the same machine.

The app ID follows Flathub's rule for GitHub-hosted apps, `io.github.<user>.<repo>`, which is
why the repository is named `NoPapersNeeded`. To submit to Flathub, run `mise flathub-manifest
<version>` after that version's release is published. It writes
`desktopApp/build/flathub/` with the manifest pointing at the release's Linux tarball and a
`flathub.json` limiting the build to x86_64. Its `x-checker-data` lets Flathub's bot open an
update pull request for every later release, so the task is only needed again when the
manifest itself changes. Flathub also wants screenshots in the metainfo before it accepts
the app. Flathub installs `app/` with the modes the tarball stores, so the tarball keeps the
staged files' modes (Gradle archives store fixed ones by default), and `packageLinuxTarball`
fails if the launcher or any other executable lost its execute bit.

### Updates

Every desktop build except the Flatpak, and the APK, looks up the latest GitHub release once
per start and shows a banner with a download link when it is newer (`dev.npn.core.update`).
The check is a setting under About, on by default; dismissing the banner hides that version
for good. Inside Flatpak the check is off and hidden, since Flathub delivers updates.

The AppImage embeds update information pointing at the `.zsync` of the newest release on its
channel, so AppImageUpdate and launchers such as Gear Lever can update it in place; `packageAppImage`
writes that `.zsync` next to the AppImage. On 0.x the channel is `latest-pre` (the newest beta), a
value Gear Lever and the classic AppImageUpdate read as a tag name and therefore ignore: a beta
AppImage updates through the app's own banner. `latest` from 1.0.0 onwards means the newest stable
release.

On Windows, the release workflow submits each stable version's `.msi` to winget once the first
version is in `microsoft/winget-pkgs` (submit that one by hand with `komac new`) and the
`WINGET_TOKEN` secret holds a classic token with `public_repo`. A beta is never submitted — winget
has no pre-releases and would hand it to every winget user as the stable package — so the job is
skipped for one. The action needs `cargo-bins/cargo-binstall` allowed and pinned, which this
repository's allowed-actions policy currently refuses, so until that changes a stable submission is
manual too.

### Continuous integration

The repo is public, so standard runners and artifact storage cost nothing.

- `ci.yml` runs on every pull request and push, with no paths filter: its `build` job is the
  check the `main` ruleset requires, so it must report on every pull request (renaming the job
  means updating the ruleset). Every run uploads the debug APK, the AppImage and the coverage
  report, kept a week.
- `windows.yml` (the Windows build) and `codeql.yml` run on pull requests, pushes to `main`, and
  weekly. `secrets.yml` scans for committed secrets.
- `mutation.yml` (hours) and `simulation.yml` (up to half an hour) run on demand only.
- Pull requests from forks wait for a maintainer's approval before any workflow runs.
- Only a `push` or `workflow_dispatch` run counts as CI on a commit
  (`.github/scripts/ci-status.sh`): a `pull_request` run tested the pull request merged into its
  base branch, not the commit itself. `mise release` and `release.yml` both rely on that (see
  Releases).

### Releases

The version lives in one place: `npn.version` in `gradle.properties`. The Android
`versionName`, the Android `versionCode` (`major*10000 + minor*100 + patch`), the desktop
package version and the About screen all read it. It must be plain `major.minor.patch`
(jpackage/MSI accept nothing else; major ≤ 255, minor and patch ≤ 99).

To release, from an up-to-date `main` that CI has passed:

```
mise release 1.2.0
```

That bumps `npn.version`, commits `[main] Release 1.2.0`, tags `v1.2.0` and pushes both. The first
release has no earlier version to bump from: `mise release 0.1.0` while `npn.version` is already
`0.1.0` tags the current commit instead, since no `v0.1.0` tag exists yet.

Everything before 1.0.0 is a beta. The version cannot say so (jpackage and MSI take plain numbers
only), so the release workflow takes the channel from the major version: a 0.x release is published
as a GitHub pre-release, titled `v0.2.0 (beta)`. That keeps a beta out of "latest", which the
AppImage's zsync update info and Flathub's external-data checker read, while a 0.x build's own
update check asks for pre-releases too (a 1.x build is only offered stable releases). winget has no
pre-releases, so a beta is never submitted there. Flathub likewise only follows a stable release.

It refuses unless `main` matches `origin/main` and the CI workflow has passed on that commit,
which it asks `gh` (the GitHub CLI, logged in). Only a run on a push or a manual run
(`gh workflow run ci.yml --ref main`) counts: a pull request's run tested the pull request merged
into its base branch, not the commit itself. The tag goes on the bump commit, which changes
only `npn.version`: CI runs on it as on any push to `main`, but the release workflow neither
waits for that run nor reruns the tests.
The tag starts `.github/workflows/release.yml`. Before it builds anything, it refuses a tag that
does not match `npn.version`, and a tagged commit CI has not passed on unless it changes only the
`npn.version` line on top of a `main` commit CI passed on
(`.github/scripts/check-release-commit.sh`, which counts runs with the same `ci-status.sh`). It then builds the signed APK, AppImage (with its
`.zsync`), `.deb`, `.rpm`, `.flatpak`, Linux tarball, `.msi` and `.exe` into a draft release,
adds `SHA256SUMS` and publishes it. If a job fails the draft stays unpublished. For a transient
failure (a download, a runner), re-run the failed jobs: their uploads replace what an earlier
attempt left on the draft, and the checksums are taken afresh. A failure that needs a code change:
fix it, delete the draft and the tag, and release the next patch version.

The APK is signed with the release key, read from the environment:
`NPN_KEYSTORE_FILE`, `NPN_KEYSTORE_PASSWORD` and `NPN_KEY_ALIAS`. The workflow gets them
from the repository secrets `NPN_KEYSTORE_BASE64` (the keystore, base64-encoded),
`NPN_KEYSTORE_PASSWORD` and `NPN_KEY_ALIAS`. Only the step that builds the APK sees them, before
any downloaded tool runs: it decodes the keystore, runs Gradle with no daemon to outlive it, and
deletes the keystore when it ends. `GH_TOKEN` is likewise set only on the steps that call `gh`,
every action is pinned to a commit (in every workflow), and the build jobs restore no Gradle
cache, since Gradle does not re-verify what the other workflows' caches hold. The keystore is never committed. Keep a
backup: Android only installs an update signed with the same key, so losing it means
users have to uninstall and reinstall. The committed `npn-debug.keystore` is for debug
builds only.

### App icon

The artwork lives in `design/branding/icon.svg` (see `design/branding/README.md`).
Android reads it as an adaptive icon built from vector drawables in
`androidApp/src/main/res/`, so nothing needs rendering there. The desktop
distributions need bitmaps, so `desktopApp/src/main/resources/icon.png` (window icon
plus the Linux/AppImage icon) and `icon.ico` (Windows) are committed and regenerated
from the SVG with `mise icons`.

## Library folder layout

```
Library/
  ... your PDFs and subfolders ...
  inbox/                                # optional import/scan target
  .npn/
    journal/objects-v2-<00..ff>/
      <sha256>.jsonl                    # content-addressed metadata transactions
    journal/objects-snap-v2-<00..ff>/   # snapshots earlier builds compacted (read, never written)
    ocr/<sha256>.<deviceId>.<engine>.json # OCR sidecars, adopted across devices
    devices/<deviceId>/<installationId>.json # which installations use a device id
    trash/                              # soft-deleted files (synced, not indexed)
    tmp/                                # import staging; crash leftovers swept after a day
```

The `.npn/` directory syncs with the documents. The device-local index (`index.db`), this
device's own state (`device.db`) and cached thumbnails live outside the library folder and
are never synced. Library settings (review mode, storage-path rule, auto-match rules, saved
views) are journal transactions like any other metadata, so every device shares them.

Metadata uses content-addressed storage-v2 objects. Metadata writes require verified
publication with atomic create-without-replacement semantics, supported by all current
desktop and Android libraries. A library on storage that cannot guarantee this refuses
to open, at every open and with its own message, rather than falling back to another storage
format. The app never compacts the journal: every transaction stays as its own small object
under `journal/objects-v2-*/`, so that folder grows by one file per metadata change for the
life of the library. Thousands of small files there are the expected shape, not damage; a sync
tool handles them like any other files.
A storage-v1 segment (`journal/<deviceId>/<seq>.jsonl`), a format no released build ever wrote, is
no longer read. The `devices/` markers tie each device id to the one installation that uses it, so a
restored or cloned installation takes a fresh id rather than writing under another's name.

### Android storage

On Android the library is, by default, a plain folder on shared storage
(`Documents/NoPapersNeeded` is proposed on first run) that the app reads and writes
directly with the same filesystem implementation as the desktop app. That is what lets
Syncthing (or any other sync tool that works with real paths) share the folder: the app
watches it with inotify, rescans whenever it returns to the foreground, and its `.npn/`
metadata syncs along with the PDFs. Seeing files those tools write requires the
"All files access" grant (`MANAGE_EXTERNAL_STORAGE`), which onboarding asks for; without
it the app can only see its own files. Shared storage has no hard links, so verified
publication there uses `renameat2(RENAME_NOREPLACE)` through JNA to install the
hash-verified staging object without replacing a concurrently synced file. If the
filesystem lacks that primitive, the operation fails rather than risking replacement.
Moves across mount points likewise fail without removing the source.

The Storage Access Framework remains available as an advanced option
(Settings → Library → Advanced → *Use Storage Access Framework*) for folders that have
no path, such as cloud document providers, and every library chosen through SAF before
the direct mode existed keeps opening that way. Cloud sync (Settings → Cloud sync) reaches
Dropbox, Drive, OneDrive, WebDAV/Nextcloud and the rest through the bundled rclone, set up
in the app rather than through SAF.
