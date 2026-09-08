package dev.npn.app.di

import android.content.Context
import androidx.core.net.toUri
import dev.npn.core.app.AppDirs
import dev.npn.core.app.DirThumbnailStore
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.SettingsRepositoryImpl
import dev.npn.core.app.toAiConfig
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.SafLibraryFileSystem
import dev.npn.core.ocr.MlKitOcrEngine
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfBoxAndroidEngine
import dev.npn.core.watch.SafWatchedFolder
import dev.npn.core.xmp.PdfBoxAndroidXmpIO
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Locale

/**
 * Android [AppEnvironment] factory. Builds a [DefaultAppEnvironment] with a SharedPreferences
 * [SettingsRepositoryImpl] and a factory that wires SAF filesystem + pdfbox-android + MLKit for
 * each opened library. The Room database name is suffixed with a stable hash of the SAF tree uri
 * so each library gets its own cache; thumbnails live under the app cache dir.
 */
fun createAppEnvironment(context: Context): AppEnvironment {
    val appContext = context.applicationContext
    val settings = SettingsRepositoryImpl(appContext)
    val watchedFolders =
        WatchedFolderFactory { location ->
            runCatching { SafWatchedFolder(appContext, location.toUri()) }.getOrNull()
        }
    return DefaultAppEnvironment(settings, watchedFolders) { location, scope ->
        val key = AppDirs.libraryKey(location)
        val fs = SafLibraryFileSystem(appContext, location.toUri())
        val db = npnDatabase(appContext, "npn-index-$key.db")

        val thumbsDir = File(appContext.cacheDir, "thumbs/$key").apply { mkdirs() }

        val deps =
            SessionDeps(
                fs = fs,
                db = db,
                pdfEngine = PdfBoxAndroidEngine(appContext),
                ocrEngine = MlKitOcrEngine(),
                xmpIO = PdfBoxAndroidXmpIO(appContext),
                thumbnailStore = DirThumbnailStore(thumbsDir.absolutePath),
                scope = scope,
                ocrLanguage = { settings.settings.first().ocrLanguage },
                trashRetentionDays = { settings.settings.first().trashRetentionDays },
                installationId = settings.settings.first().installationId,
                storagePathRule = {
                    val snapshot = settings.settings.first()
                    val template = snapshot.storagePathTemplate
                    if (template.isNullOrBlank()) null else StoragePathRule(template, snapshot.storagePathEnabled)
                },
                autoMatchRulesJson = { settings.settings.first().autoMatchRulesJson },
                persistAutoMatchRulesJson = { json -> settings.edit { it.copy(autoMatchRulesJson = json) } },
                // Locale.getDefault() is what LocalAppLocale sets when the user picks a UI
                // language, so it doubles as the effective app language here; read per call
                // because it changes while the app runs.
                aiConfig = { settings.settings.first().toAiConfig(Locale.getDefault().language) },
            )
        OpenedLibrary(deps) { deps.closeDb() }
    }
}
