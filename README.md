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
- Metadata is a journal. Changes (tags, properties, trash, relink) are appended to
  per-device, write-once segment files under `.npn/journal/`. No file is ever
  modified after it is written, and no two devices write the same file, so sync
  conflicts on app data are structurally impossible. Merges are conflict-free:
  OR-set semantics for tags, hybrid-logical-clock last-writer-wins for single-value
  properties.
- SQLite is a rebuildable cache. The index (search, thumbnails, folded journal
  state) lives in device-local storage, never inside the library folder, and is
  never synced. Delete it and it rebuilds from the folder.
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
- Review queue for inbox and untagged documents.
- Kanban board with configurable grouping and drag-and-drop between lanes.
- Trash with restore and configurable auto-purge.
- Maintenance: duplicate detection, missing-file detection and relink flow.
- Background OCR: MLKit on Android, Tesseract CLI on desktop.
- Cached first-page thumbnails; list, grid and kanban views.

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
    journal/<deviceId>/<seq>.jsonl      # per-device, write-once metadata segments
    ocr/<sha256>.<deviceId>.json        # OCR sidecars, adopted across devices
    trash/                              # soft-deleted files (synced, not indexed)
```

The `.npn/` directory syncs with the documents. The device-local index and cached
thumbnails live outside the library folder and are never synced.

## License

Apache-2.0. See [LICENSE](LICENSE).
