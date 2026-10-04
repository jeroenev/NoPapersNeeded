package dev.npn.app.di

import android.content.Context
import androidx.core.net.toUri
import dev.npn.app.ui.common.hasDirectStorageAccess
import dev.npn.core.app.AppDirs
import dev.npn.core.app.DirThumbnailStore
import dev.npn.core.app.LibraryLocations
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.SettingsRepositoryImpl
import dev.npn.core.app.toAiConfig
import dev.npn.core.db.deviceDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.ContentResolverExternalIo
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.SafLibraryFileSystem
import dev.npn.core.ocr.MlKitOcrEngine
import dev.npn.core.ocr.OcrLanguages
import dev.npn.core.pdf.PdfBoxAndroidEngine
import dev.npn.core.util.AndroidLog
import dev.npn.core.watch.JvmWatchedFolder
import dev.npn.core.watch.SafWatchedFolder
import dev.npn.core.watch.WatchedFolder
import dev.npn.core.xmp.PdfBoxAndroidXmpIO
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale

/**
 * Android [AppEnvironment] factory. Builds a [DefaultAppEnvironment] with a SharedPreferences
 * [SettingsRepositoryImpl] and a factory that wires the library filesystem + pdfbox-android +
 * MLKit for each opened library. The filesystem follows the location string
 * ([LibraryLocations]): a plain path opens the same direct [JvmLibraryFileSystem] the desktop app
 * uses, which is what lets Syncthing share the folder; a `content://` tree uri opens the
 * [SafLibraryFileSystem], kept for cloud document providers and for libraries chosen before the
 * direct mode existed. The Room database names are suffixed with a stable hash of the location so
 * each library gets its own index and device state; thumbnails live under the app cache dir.
 */
fun createAppEnvironment(context: Context): AppEnvironment {
    val appContext = context.applicationContext
    val settings = SettingsRepositoryImpl(appContext)
    val watchedFolders =
        WatchedFolderFactory { location ->
            runCatching { watchedFolder(appContext, location) }.getOrNull()
        }
    return DefaultAppEnvironment(settings, watchedFolders, AndroidLog) { location, scope ->
        val key = AppDirs.libraryKey(location)
        val fs = libraryFileSystem(appContext, location)
        val db = npnDatabase(appContext, "npn-index-$key.db")
        val deviceDb = deviceDatabase(appContext, "npn-device-$key.db")

        val thumbsDir = File(appContext.cacheDir, "thumbs/$key").apply { mkdirs() }

        val deps =
            SessionDeps(
                fs = fs,
                db = db,
                deviceDb = deviceDb,
                pdfEngine = PdfBoxAndroidEngine(appContext),
                ocrEngine = MlKitOcrEngine(),
                xmpIO = PdfBoxAndroidXmpIO(appContext),
                thumbnailStore = DirThumbnailStore(thumbsDir.absolutePath),
                scope = scope,
                ocrLanguage = {
                    OcrLanguages.effective(settings.settings.first().ocrLanguage, Locale.getDefault().language)
                },
                trashRetentionDays = { settings.settings.first().trashRetentionDays },
                log = AndroidLog,
                installationId = settings.settings.first().installationId,
                legacyLibrarySettings = settings::legacyLibrarySettings,
                // Locale.getDefault() is what LocalAppLocale sets when the user picks a UI
                // language, so it doubles as the effective app language here; read per call
                // because it changes while the app runs.
                aiConfig = { settings.settings.first().toAiConfig(Locale.getDefault().language) },
            )
        OpenedLibrary(deps) { deps.closeDb() }
    }
}

private fun watchedFolder(
    context: Context,
    location: String,
): WatchedFolder =
    if (LibraryLocations.isContentUri(location)) {
        SafWatchedFolder(context, location.toUri())
    } else {
        JvmWatchedFolder(Paths.get(location))
    }

private fun libraryFileSystem(
    context: Context,
    location: String,
): LibraryFileSystem {
    if (LibraryLocations.isContentUri(location)) return SafLibraryFileSystem(context, location.toUri())
    val root = Paths.get(location)
    // Fail here, with the reason, rather than let the first listing come back empty and be read as
    // "every document was deleted": the folder may be on an unmounted SD card, or the all-files
    // grant may have been revoked in system settings since the library was chosen.
    if (!Files.isDirectory(root)) {
        throw LibraryUnavailableException(OpenFailureReason.FOLDER_MISSING, "Library folder not found: $location")
    }
    if (!hasDirectStorageAccess()) {
        throw LibraryUnavailableException(
            OpenFailureReason.NO_STORAGE_ACCESS,
            "Storage access was revoked; allow \"All files access\" for No Papers Needed to open $location",
        )
    }
    // Shared storage is a FUSE mount without link(2), so objects publish by exclusive rename there.
    return JvmLibraryFileSystem(root, ContentResolverExternalIo(context))
}
