# No papers needed

A local-first paperless document manager for Android and Linux desktop. Your files
stay plain PDFs in a folder you own. There is no server, no account, and no
proprietary database holding your documents hostage. Sync the folder with any file
sync tool you already use (Syncthing, Nextcloud, etc.) and every device sees the
same library.

## Design

- Files are the source of truth. The library is a folder of PDFs plus a small
  `.npn/` metadata directory. Any device with the folder has everything, and the
  app can rebuild all of its state from the folder alone.
- Documents are immutable. Once a PDF is in the library the app never rewrites its
  bytes (rename/move only). The one exception is the explicit bake-in-place action.
- Metadata is a journal. Changes (tags, properties, trash, relink) are stored as
  immutable JSONL transactions under `.npn/journal/`. New-format transactions use
  their complete-file SHA-256 as the filename; legacy per-device segments remain
  readable. No published transaction is modified after it is written. Merges are conflict-free:
  OR-set semantics for tags, hybrid-logical-clock last-writer-wins for single-value
  properties.
- SQLite is a rebuildable cache. The index (search, thumbnails, source provenance,
  and incremental metadata projection) lives in device-local storage, never inside
  the library folder, and is never synced. Delete it and it rebuilds from the folder.
- OCR travels as sidecars. Extracted text is written to `.npn/ocr/` keyed by file
  hash and device id, so a device adopts another device's OCR instead of redoing it.
- Identity is the SHA-256 of the file bytes. Renames and moves never detach
  metadata, and imports dedup by hash.
- XMP is baked on export. Exported/shared copies get `dc:subject` keywords and an
  `npn:` namespace embedded for interoperability with other tools; the library file
  is untouched unless you explicitly bake in place.

## Features (v1)

- Import and scan: MLKit document scanner on Android, file dialog and drag-and-drop
  on desktop, plus an optional watched inbox folder.
- Share-sheet import on Android (PDFs and images; images are converted to PDF).
- Full-text search over filenames, content and tags using SQLite FTS5, with
  filters and snippets.
- Hierarchical tags with autocomplete, bulk tagging, rename/merge/delete.
- Correspondents and document types managed like tags.
- Saved views: named filter/sort/view combinations, pinnable.
- Review queue with a choice of workflow (synced across devices): "untagged only" (documents just appear, needing review until tagged) or "inbox workflow" (an explicit reviewed marker you set by hand).
- Trash with restore and configurable auto-purge.
- Maintenance: duplicate detection, missing-file detection and relink flow.
- Background OCR: MLKit on Android, Tesseract CLI on desktop.
- Cached first-page thumbnails; list and grid views.

## Requirements

- Android 8.0+ (API 26).
- Desktop: Java 17 or newer. Packaged distributions bundle their own runtime, so no
  system JVM is required to run them.
- Optional on desktop: `tesseract-ocr` on `PATH` for OCR. Without it, only embedded
  PDF text is indexed on desktop.

## Building

The Gradle build uses a Java 17 toolchain provisioned via the foojay resolver, so a
matching JDK is downloaded automatically if needed. The Android SDK is located via
`local.properties` (`sdk.dir`) or the `ANDROID_HOME` / `ANDROID_SDK_ROOT`
environment variable.

### Task runner (mise)

Common build, run, test, and device-deploy commands are wrapped as
[mise](https://mise.jdx.dev) tasks in `mise.toml` (plus file tasks in
`.mise/tasks/`). Run `mise tasks` to list them, and `mise run <task>` (or just
`mise <task>`) to execute one. The raw Gradle commands each task wraps are shown
below if you prefer to run them directly.

| Task | Wraps |
|------|-------|
| `mise apk` | `./gradlew :androidApp:assembleDebug` |
| `mise apk-release` | `./gradlew :androidApp:assembleRelease` (unsigned) |
| `mise bundle` | `./gradlew :androidApp:bundleRelease` |
| `mise deploy` | `installDebug` over adb, then launches the app (add `--release` for the release build) |
| `mise uninstall` | `adb uninstall dev.npn` |
| `mise pair` | one-time adb Wi-Fi pairing wizard (Android 11+) |
| `mise connect <ip:port>` | `adb connect` for an already-paired device |
| `mise devices` | `adb devices -l` |
| `mise hot` | `./gradlew :desktopApp:hotRun --auto` (Compose Hot Reload) |
| `mise desktop` | `./gradlew :desktopApp:run` |
| `mise package-deb` | `./gradlew :desktopApp:packageDistributionForCurrentOS` |
| `mise package-appimage` | `./gradlew :desktopApp:packageAppImage` |
| `mise package-jar` | `./gradlew :desktopApp:packageUberJarForCurrentOS` |
| `mise test` | `./gradlew :core:desktopTest` |
| `mise test-all` | `./gradlew allTests :core:desktopTest` |
| `mise check` | `./gradlew check` |
| `mise build` | `./gradlew build` |
| `mise clean` | `./gradlew clean` |

### Deploying to an Android device

To iterate on a physical device without a cable, pair once over Wi-Fi
(Android 11+, phone and workstation on the same network), then deploy with a
single command:

```
mise pair      # one time: follow the prompts, uses the phone's Wireless debugging screen
mise deploy    # every time: build + install over adb + launch dev.npn/.MainActivity
```

`mise deploy` runs the Android Gradle Plugin `installDebug` task (build + adb
install) and then `adb shell am start -n dev.npn/.MainActivity`. If the device
drops off the network later, reconnect with `mise connect <ip:port>` (no
re-pairing needed). A USB-connected device works too; skip `mise pair`.

For fast UI iteration without a device, use `mise hot`: the Compose UI in
`composeApp` runs on the desktop target with Compose Hot Reload, so edits to
shared composables update live on save. Android-only code paths (document
scanner, share intents, WorkManager) are not exercised there; use `mise deploy`
for those.

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

Desktop, build a native distribution for the current OS:

```
./gradlew :desktopApp:packageDistributionForCurrentOS
# output: desktopApp/build/compose/binaries/main/deb/no-papers-needed_<version>_amd64.deb
```

Note: only the `.deb` format is enabled by default because building `.rpm` requires
the `rpmbuild` tooling, which is not present on Debian/Ubuntu build hosts. To also
build an `.rpm`, install the rpm-build tooling and add `TargetFormat.Rpm` to the
`nativeDistributions` block in `desktopApp/build.gradle.kts`.

Desktop, build a Linux AppImage:

```
./gradlew :desktopApp:packageAppImage
# output: desktopApp/build/appimage/no-papers-needed-<version>-x86_64.AppImage
```

jpackage cannot emit AppImage directly, so this task wraps the jpackage app-image
(bundled JRE plus launcher) with `appimagetool`. `appimagetool` is downloaded
automatically into `desktopApp/build/appimage/` on first run, so this task needs
network access. It builds with `--appimage-extract-and-run` and therefore does not
need FUSE on the build host; running the resulting AppImage still needs FUSE, or the
end user can run it with `--appimage-extract-and-run`. The AppImage is x86_64 only.
The bundled icon is a placeholder; wire `nativeDistributions.linux.iconFile` to ship
real branding.

Run the tests:

```
./gradlew :core:desktopTest
```

## Library folder layout

```
Library/
  ... your PDFs and subfolders ...
  inbox/                                # optional import/scan target
  .npn/
    journal/<deviceId>/<seq>.jsonl      # legacy per-device metadata segments
    journal/objects-v2-<00..ff>/
      <sha256>.jsonl                    # content-addressed metadata transactions
    ocr/<sha256>.<deviceId>.json        # OCR sidecars, adopted across devices
    trash/                              # soft-deleted files (synced, not indexed)
```

The `.npn/` directory syncs with the documents. The device-local index and cached
thumbnails live outside the library folder and are never synced.

Metadata is written as content-addressed storage-v2 objects on every storage
provider that supports verified publication (all current desktop and Android/SAF
libraries). Legacy per-device v1 segments are no longer produced but remain fully
readable forever, so libraries written by older releases keep folding correctly.
A provider without a proven atomic create-without-replacement primitive (an unknown
future backend) would fall back to writing legacy v1 segments.

## License

Apache-2.0. See [LICENSE](LICENSE).
