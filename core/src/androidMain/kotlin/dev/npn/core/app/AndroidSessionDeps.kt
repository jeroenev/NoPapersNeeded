package dev.npn.core.app

import android.content.Context
import dev.npn.core.ai.AiPriceSource
import dev.npn.core.db.deviceDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.ContentResolverExternalIo
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.ocr.MlKitOcrEngine
import dev.npn.core.pdf.DirThumbnailStore
import dev.npn.core.pdf.PdfBoxAndroidEngine
import dev.npn.core.pdf.renderParallelism
import dev.npn.core.settings.LibraryLocations
import dev.npn.core.settings.SessionSettings
import dev.npn.core.sync.DirectoryStaging
import dev.npn.core.sync.RcloneLaunch
import dev.npn.core.sync.RcloneTransport
import dev.npn.core.util.AndroidLog
import dev.npn.core.util.NpnLog
import dev.npn.core.xmp.PdfBoxAndroidXmpIO
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Paths

/**
 * The one place that knows how to wire a library on Android, the counterpart of `jvmSessionDeps`:
 * pdfbox-android, MLKit, and Room databases named after a stable hash of [location], so each
 * library gets its own index and device state. Thumbnails live under the app cache dir.
 *
 * [location] is a path: the library opens the same direct [JvmLibraryFileSystem] the desktop app
 * uses, which is what lets Syncthing share the folder; a cloud library's is its working copy in the
 * app's own storage. Whether the location can be opened at all (the folder is there, the storage
 * grant is held) is the caller's to check first.
 */
fun androidSessionDeps(
    context: Context,
    location: String,
    scope: CoroutineScope,
    settings: SessionSettings,
    installationId: String,
    log: NpnLog = AndroidLog,
    /** Where current model prices come from; the app passes the network one. */
    priceSource: AiPriceSource? = null,
): SessionDeps {
    val key = LibraryLocations.key(location)
    // DirThumbnailStore creates the directory with its first thumbnail.
    val thumbsDir = File(context.cacheDir, "thumbs/$key")
    val syncTransport = RcloneTransport(androidRclone(context))
    return SessionDeps(
        // Shared storage is a FUSE mount without link(2), so objects publish by exclusive rename there.
        fs = JvmLibraryFileSystem(Paths.get(location), ContentResolverExternalIo(context)),
        db = npnDatabase(context, "npn-index-$key.db"),
        deviceDb = deviceDatabase(context, "npn-device-$key.db"),
        pdfEngine = PdfBoxAndroidEngine(context),
        ocrEngine = MlKitOcrEngine(),
        xmpIO = PdfBoxAndroidXmpIO(context),
        thumbnailStore = DirThumbnailStore(thumbsDir.absolutePath),
        // Below desktop's 4: every render holds a page bitmap, and phones have less memory.
        renderParallelism = renderParallelism(max = 3),
        scope = scope,
        settings = settings,
        log = log,
        installationId = installationId,
        syncTransport = syncTransport,
        syncSetup = syncTransport.setup,
        syncStaging = DirectoryStaging(File(context.cacheDir, "sync/$key").toPath()),
        priceSource = priceSource,
    )
}

/**
 * The bundled rclone, with its config file in the app's private no-backup files (it holds the
 * remotes' sign-in tokens, which Auto Backup must not copy to the cloud or to another phone) and its
 * cache in the app's cache, both made up front so rclone never has to make them. Every library's
 * engine and the onboarding's setup engine share it.
 */
fun androidRclone(context: Context): RcloneLaunch =
    RcloneLaunch(
        binary = bundledRclone(context) ?: "rclone",
        configPath = File(context.noBackupFilesDir, "rclone/rclone.conf").apply { parentFile?.mkdirs() }.path,
        cacheDir = File(context.cacheDir, "rclone").apply { mkdirs() }.path,
    )

/**
 * The rclone executable shipped inside the APK, when the build includes one. Android only lets an
 * app execute files from its native library directory, so a bundled rclone travels as
 * `jniLibs/<abi>/librclone.so` (the Round Sync convention) and is run from there as a subprocess.
 * Absent that file the transport falls back to PATH, which on a device means "not installed".
 */
private fun bundledRclone(context: Context): String? {
    val file = File(context.applicationInfo.nativeLibraryDir, "librclone.so")
    return file.takeIf { it.canExecute() }?.absolutePath
}
