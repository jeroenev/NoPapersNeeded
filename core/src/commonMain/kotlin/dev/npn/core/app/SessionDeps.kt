package dev.npn.core.app

import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.settings.SessionSettings
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.SystemEpochClock
import dev.npn.core.xmp.PdfXmpIO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.time.TimeSource

/**
 * Bundle of everything a library session needs ([openSession]), assembled per platform:
 * `jvmSessionDeps` on a plain JVM, `androidSessionDeps` on Android.
 */
class SessionDeps(
    val fs: LibraryFileSystem,
    /** The disposable index: dropped and rebuilt from the library whenever it must be. */
    val db: NpnDatabase,
    /** This device's own state for the library, which nothing could rebuild ([DeviceDatabase]). */
    val deviceDb: DeviceDatabase,
    val pdfEngine: PdfEngine,
    val ocrEngine: OcrEngine,
    val xmpIO: PdfXmpIO,
    val thumbnailStore: ThumbnailStore,
    /** How many pages render at once, thumbnails and page views together; the platform's pick. */
    val renderParallelism: Int,
    val scope: CoroutineScope,
    val settings: SessionSettings,
    /** Where the session reports what fails in the background, with no caller to tell. */
    val log: NpnLog,
    val clock: EpochClock = SystemEpochClock,
    val installationId: String,
    /**
     * Where the session's CPU-bound work runs: journal folds and writes, file hashing, list mapping,
     * page rendering. A test passes its own dispatcher, so all of it runs on virtual time.
     */
    val computation: CoroutineDispatcher = Dispatchers.Default,
    /** What measures the folder watch's settle windows; a test passes its scheduler's. */
    val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /**
     * Close the index and device databases. Exposed here so the composeApp DI layer (which does not
     * depend on the androidx.room3 runtime directly) can free them on a library swap without
     * touching their room supertype.
     */
    fun closeDb() {
        db.close()
        deviceDb.close()
    }
}
