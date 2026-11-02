package dev.npn.app.di

import dev.npn.app.platform.OcrModelSource
import dev.npn.app.platform.OcrModels
import dev.npn.core.app.DesktopLog
import dev.npn.core.app.desktopRclone
import dev.npn.core.app.jvmSessionDeps
import dev.npn.core.ocr.TesseractCliEngine
import dev.npn.core.settings.AppDirs
import dev.npn.core.settings.SessionSettings
import dev.npn.core.settings.SettingsRepositoryImpl
import dev.npn.core.sync.RcloneTransport
import dev.npn.core.watch.JvmWatchedFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Desktop [AppEnvironment] factory. Builds a [DefaultAppEnvironment] with a file-backed
 * [SettingsRepositoryImpl]; the per-library JVM wiring itself is `core`'s [jvmSessionDeps], so
 * this only checks that a location can be opened and binds the session's settings to the
 * repository.
 */
fun createAppEnvironment(): AppEnvironment {
    val settings = SettingsRepositoryImpl(DesktopLog)
    return DefaultAppEnvironment(
        settings,
        watchedFolder = { location -> JvmWatchedFolder(Path.of(location)) },
        log = DesktopLog,
        cloudSetup = { RcloneTransport(desktopRclone()).let { CloudSetupEngine(it.setup, it::close) } },
        readOcrModels = ::desktopOcrModels,
    ) { location, scope ->
        val root = Path.of(location)
        if (!Files.isDirectory(root)) throw LibraryUnavailableException.folderMissing(location)
        jvmSessionDeps(
            libraryRoot = root,
            dirs = AppDirs.forLibrary(location),
            scope = scope,
            settings = SessionSettings.from(settings),
            installationId = settings.settings.first().installationId,
            ocrEngine = bundledTesseract() ?: TesseractCliEngine(),
            log = DesktopLog,
        )
    }
}

/**
 * The models the Tesseract this app runs has, and where a missing one comes from: the app's own
 * package where it ships them (a Windows build, the Flatpak), else the system's package manager.
 */
private suspend fun desktopOcrModels(): OcrModels =
    withContext(Dispatchers.IO) {
        val bundled = bundledTesseract()
        val installed = (bundled ?: TesseractCliEngine()).installedLanguages()
        val flatpak = !System.getenv("FLATPAK_ID").isNullOrBlank()
        OcrModels(installed, if (bundled != null || flatpak) OcrModelSource.BUNDLED else systemPackages())
    }

/** The package manager `/etc/os-release` names this Linux as a kin of, if the app knows it. */
private fun systemPackages(): OcrModelSource {
    val release =
        runCatching { File("/etc/os-release").readLines() }
            .getOrDefault(emptyList())
            .filter { it.startsWith("ID=") || it.startsWith("ID_LIKE=") }
            .flatMap { it.substringAfter('=').trim('"').split(' ') }
            .toSet()
    return when {
        release.any { it in APT_SYSTEMS } -> OcrModelSource.APT
        release.any { it in DNF_SYSTEMS } -> OcrModelSource.DNF
        "arch" in release -> OcrModelSource.PACMAN
        else -> OcrModelSource.OTHER
    }
}

private val APT_SYSTEMS = setOf("debian", "ubuntu")
private val DNF_SYSTEMS = setOf("fedora", "rhel", "centos")

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
