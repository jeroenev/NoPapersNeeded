package dev.npn.app.di

import dev.npn.core.ai.AiConfig
import dev.npn.core.app.AppDirs
import dev.npn.core.app.DirThumbnailStore
import dev.npn.core.app.PlatformDirs
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.SettingsRepositoryImpl
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.ocr.TesseractCliEngine
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfBoxEngine
import dev.npn.core.watch.JvmWatchedFolder
import dev.npn.core.xmp.PdfBoxXmpIO
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Path

/**
 * Desktop [AppEnvironment] factory. Builds a [DefaultAppEnvironment] with a file-backed
 * [SettingsRepositoryImpl] and a factory that wires java.nio filesystem + PDFBox + Tesseract for
 * each opened library. App-local storage follows the platform convention
 * (XDG_DATA_HOME on Unix, LOCALAPPDATA on Windows): `no-papers-needed/<libraryKey>/`.
 */
fun createAppEnvironment(): AppEnvironment {
    val settings = SettingsRepositoryImpl()
    val watchedFolders =
        WatchedFolderFactory { location ->
            runCatching { JvmWatchedFolder(Path.of(location)) }.getOrNull()
        }
    return DefaultAppEnvironment(settings, watchedFolders) { location, scope ->
        val dirs = desktopAppDirs(location)
        val fs = JvmLibraryFileSystem(Path.of(location))
        val db = npnDatabase(File(dirs.dbDir, "index.db"))
        File(dirs.thumbsDir).mkdirs()
        File(dirs.tmpDir).mkdirs()

        val deps =
            SessionDeps(
                fs = fs,
                db = db,
                pdfEngine = PdfBoxEngine(),
                ocrEngine = TesseractCliEngine(),
                xmpIO = PdfBoxXmpIO(),
                thumbnailStore = DirThumbnailStore(dirs.thumbsDir),
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
                persistAutoMatchRulesJson = settings::setAutoMatchRulesJson,
                aiConfig = {
                    val snapshot = settings.settings.first()
                    AiConfig(
                        enabled = snapshot.aiEnabled,
                        baseUrl = snapshot.aiBaseUrl,
                        apiKey = snapshot.aiApiKey,
                        model = snapshot.aiModel,
                        maxTagsPerDoc = snapshot.aiMaxTagsPerDoc,
                        maxNewTagsPerDoc = snapshot.aiMaxNewTagsPerDoc,
                    )
                },
            )
        OpenedLibrary(deps) { deps.closeDb() }
    }
}

private fun desktopAppDirs(location: String): AppDirs {
    val dataHome = PlatformDirs.dataHome()
    val key = AppDirs.libraryKey(location)
    val base = "$dataHome/no-papers-needed/$key"
    return AppDirs(dbDir = "$base/db", thumbsDir = "$base/thumbs", tmpDir = "$base/tmp")
}
