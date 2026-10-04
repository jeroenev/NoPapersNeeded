package dev.npn.app.di

import dev.npn.core.app.AppDirs
import dev.npn.core.app.DesktopLog
import dev.npn.core.app.SettingsRepositoryImpl
import dev.npn.core.app.desktop
import dev.npn.core.app.jvmSessionDeps
import dev.npn.core.app.toAiConfig
import dev.npn.core.ocr.OcrLanguages
import dev.npn.core.ocr.TesseractCliEngine
import dev.npn.core.watch.JvmWatchedFolder
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Desktop [AppEnvironment] factory. Builds a [DefaultAppEnvironment] with a file-backed
 * [SettingsRepositoryImpl]; the per-library JVM wiring itself is `core`'s [jvmSessionDeps], so
 * this only binds the session's live knobs to the settings repository.
 */
fun createAppEnvironment(): AppEnvironment {
    val settings = SettingsRepositoryImpl()
    val watchedFolders =
        WatchedFolderFactory { location ->
            runCatching { JvmWatchedFolder(Path.of(location)) }.getOrNull()
        }
    return DefaultAppEnvironment(settings, watchedFolders, DesktopLog) { location, scope ->
        val root = Path.of(location)
        // An unplugged drive must not open as a library with every document gone.
        if (!Files.isDirectory(root)) {
            throw LibraryUnavailableException(OpenFailureReason.FOLDER_MISSING, "Library folder not found: $location")
        }
        val deps =
            jvmSessionDeps(
                libraryRoot = root,
                dirs = AppDirs.desktop(location),
                scope = scope,
                ocrLanguage = {
                    OcrLanguages.effective(settings.settings.first().ocrLanguage, Locale.getDefault().language)
                },
                trashRetentionDays = { settings.settings.first().trashRetentionDays },
                installationId = settings.settings.first().installationId,
                legacyLibrarySettings = settings::legacyLibrarySettings,
                // Locale.getDefault() is what LocalAppLocale sets when the user picks a UI
                // language, so it doubles as the effective app language here; read per call
                // because it changes while the app runs.
                aiConfig = { settings.settings.first().toAiConfig(Locale.getDefault().language) },
                ocrEngine = bundledTesseract() ?: TesseractCliEngine(),
                log = DesktopLog,
            )
        OpenedLibrary(deps) { deps.closeDb() }
    }
}

/**
 * The Tesseract a Windows build ships in its app resources (`prepareWindowsTesseract` in
 * `desktopApp/build.gradle.kts`), or null when this build has none and OCR falls back to a
 * `tesseract` on PATH. Linux packages get theirs from the system or, in the Flatpak, the manifest.
 */
private fun bundledTesseract(): TesseractCliEngine? {
    val resources = System.getProperty("compose.application.resources.dir") ?: return null
    val home = File(resources, "tesseract")
    val binary = File(home, "tesseract.exe")
    if (!binary.isFile) return null
    return TesseractCliEngine(binary = binary.absolutePath, tessdataDir = File(home, "tessdata").absolutePath)
}
