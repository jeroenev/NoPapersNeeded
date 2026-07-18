package dev.npn.app.di

import android.content.Context
import androidx.core.net.toUri
import dev.npn.core.app.AppDirs
import dev.npn.core.app.DirThumbnailStore
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.SettingsRepositoryImpl
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.SafLibraryFileSystem
import dev.npn.core.ocr.MlKitOcrEngine
import dev.npn.core.pdf.PdfBoxAndroidEngine
import dev.npn.core.xmp.PdfBoxAndroidXmpIO
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Android [AppEnvironment] factory. Builds a [DefaultAppEnvironment] with a SharedPreferences
 * [SettingsRepositoryImpl] and a factory that wires SAF filesystem + pdfbox-android + MLKit for
 * each opened library. The Room database name is suffixed with a stable hash of the SAF tree uri
 * so each library gets its own cache; thumbnails live under the app cache dir.
 */
fun createAppEnvironment(context: Context): AppEnvironment {
    val appContext = context.applicationContext
    val settings = SettingsRepositoryImpl(appContext)
    return DefaultAppEnvironment(settings) { location, scope ->
        val key = AppDirs.libraryKey(location)
        val fs = SafLibraryFileSystem(appContext, location.toUri())
        val db = npnDatabase(appContext, "npn-index-$key.db")

        val thumbsDir = File(appContext.cacheDir, "thumbs/$key").apply { mkdirs() }

        val deps = SessionDeps(
            fs = fs,
            db = db,
            pdfEngine = PdfBoxAndroidEngine(appContext),
            ocrEngine = MlKitOcrEngine(),
            xmpIO = PdfBoxAndroidXmpIO(appContext),
            thumbnailStore = DirThumbnailStore(thumbsDir.absolutePath),
            scope = scope,
            ocrLanguage = { settings.ocrLanguage.first() },
            trashRetentionDays = { settings.trashRetentionDays.first() },
            installationId = settings.installationId.first(),
        )
        OpenedLibrary(deps) { deps.closeDb() }
    }
}
