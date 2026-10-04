package dev.npn.app.di

import dev.npn.core.app.AppDirs
import dev.npn.core.app.DesktopLog
import dev.npn.core.app.SessionSettings
import dev.npn.core.app.SettingsRepositoryImpl
import dev.npn.core.app.desktop
import dev.npn.core.app.jvmSessionDeps
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
 * this only checks that a location can be opened and binds the session's settings to the
 * repository.
 */
fun createAppEnvironment(): AppEnvironment {
    val settings = SettingsRepositoryImpl()
    return DefaultAppEnvironment(
        settings,
        watchedFolder = { location -> runCatching { JvmWatchedFolder(Path.of(location)) }.getOrNull() },
        log = DesktopLog,
    ) { location, scope ->
        val root = Path.of(location)
        // An unplugged drive must not open as a library with every document gone.
        if (!Files.isDirectory(root)) {
            throw LibraryUnavailableException(OpenFailureReason.FOLDER_MISSING, "Library folder not found: $location")
        }
        jvmSessionDeps(
            libraryRoot = root,
            dirs = AppDirs.desktop(location),
            scope = scope,
            settings = SessionSettings.from(settings) { Locale.getDefault().language },
            installationId = settings.settings.first().installationId,
            ocrEngine = bundledTesseract() ?: TesseractCliEngine(),
            log = DesktopLog,
        )
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
