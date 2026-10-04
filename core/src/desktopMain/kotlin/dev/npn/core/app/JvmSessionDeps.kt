package dev.npn.core.app

import dev.npn.core.db.deviceDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.TesseractCliEngine
import dev.npn.core.pdf.PdfBoxEngine
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.util.JvmLog
import dev.npn.core.util.NpnLog
import dev.npn.core.xmp.PdfBoxXmpIO
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Path

/**
 * The one place that knows how to wire a library folder on a plain JVM: java.nio filesystem,
 * PDFBox, Tesseract, a Room index under [dirs]. Every JVM host — the desktop app, a headless
 * service, a test rig — builds its [SessionDeps] here and then calls
 * [LibrarySessionImpl.openSession]; opening, starting and closing stay with the caller, because
 * each host owns that lifecycle differently.
 *
 * Platform adapters default to the production ones and are parameters only so a host can swap
 * the pieces that legitimately vary (a test's no-op OCR engine, a fixed clock, the app's
 * [DesktopLog] in place of plain stderr). The index
 * database is `index.db` under [AppDirs.dbDir], with this device's own state in `device.db` beside
 * it — two sessions over one folder (two "devices") therefore need two [AppDirs], which is also
 * what keeps their device identities apart.
 */
fun jvmSessionDeps(
    libraryRoot: Path,
    dirs: AppDirs,
    scope: CoroutineScope,
    settings: SessionSettings,
    clock: EpochClock = SystemEpochClock,
    installationId: String = "local-installation",
    ocrEngine: OcrEngine = TesseractCliEngine(),
    thumbnailStore: ThumbnailStore = DirThumbnailStore(dirs.thumbsDir),
    log: NpnLog = JvmLog(),
): SessionDeps {
    File(dirs.thumbsDir).mkdirs()
    File(dirs.tmpDir).mkdirs()
    return SessionDeps(
        fs = JvmLibraryFileSystem(libraryRoot),
        db = npnDatabase(File(dirs.dbDir, "index.db")),
        deviceDb = deviceDatabase(File(dirs.dbDir, "device.db")),
        pdfEngine = PdfBoxEngine(),
        ocrEngine = ocrEngine,
        xmpIO = PdfBoxXmpIO(),
        thumbnailStore = thumbnailStore,
        scope = scope,
        settings = settings,
        log = log,
        clock = clock,
        installationId = installationId,
    )
}

/**
 * App-local directories for [location] under the platform data home
 * (`XDG_DATA_HOME` on Unix, `LOCALAPPDATA` on Windows): `no-papers-needed/<libraryKey>/`.
 */
fun AppDirs.Companion.desktop(location: String): AppDirs {
    val base = "${PlatformDirs.dataHome()}/no-papers-needed/${AppDirs.libraryKey(location)}"
    return AppDirs(dbDir = "$base/db", thumbsDir = "$base/thumbs", tmpDir = "$base/tmp")
}

/**
 * The desktop app's one log, shared by everything in the process: stderr plus `npn.log` in the
 * app's directory under the platform data home, beside every library's own directory, so a user
 * report has one file to attach whichever library was open.
 */
object DesktopLog : NpnLog by JvmLog(Path.of(PlatformDirs.dataHome(), "no-papers-needed", "npn.log"))
