package dev.npn.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.LocalLibraryFolders
import dev.npn.core.settings.PlatformDirs
import dev.npn.core.settings.SettingsRepositoryImpl
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Paths

actual suspend fun loadImageBitmap(
    path: String,
    io: CoroutineDispatcher,
): ImageBitmap? =
    withContext(io) {
        runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching null
            val bytes = file.readBytes()
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }

actual fun thumbCacheMaxEntries(): Int = 256

actual fun platformFileToExternalSource(file: PlatformFile): ExternalSource =
    ExternalSource(Paths.get(file.absolutePath()))

actual fun persistDirectoryAccess(
    dir: PlatformFile,
    access: FolderAccess,
): FolderPick =
    when (access) {
        FolderAccess.DIRECT -> FolderPick.Location(dir.absolutePath())
        FolderAccess.SAF -> FolderPick.Unusable
    }

actual fun supportsSafStorage(): Boolean = false

/**
 * Desktop has no system-level animation preference of its own: the app's Appearance setting is the
 * only way to reduce motion there.
 */
actual fun systemAnimationsEnabled(): Boolean = true

actual fun ocrHasLanguageSetting(): Boolean = true

/**
 * Email import needs a keyring for the password: the secret service on Linux, the Credential
 * Manager on Windows. Other desktops get no dead pane.
 */
actual fun supportsEmailImport(): Boolean =
    System.getProperty("os.name").lowercase().let { it.contains("linux") || it.startsWith("windows") }

/**
 * `NoPapersNeeded` in the user's documents folder (XDG's, on Linux): where file managers and sync
 * tools look, and the one place the Flatpak may keep a library (`--filesystem=xdg-documents`).
 */
actual fun defaultLibraryLocation(): String? = File(documentsDir(), DEFAULT_LIBRARY_DIR).path

/** The library folder [defaultLibraryLocation] proposes, in the user's documents folder. */
private const val DEFAULT_LIBRARY_DIR = "NoPapersNeeded"

/**
 * The user's documents folder: XDG's `XDG_DOCUMENTS_DIR` where `user-dirs.dirs` names one, else
 * `Documents` in the home folder (Windows too).
 */
private fun documentsDir(): File {
    val home = System.getProperty("user.home")
    val configHome = System.getenv("XDG_CONFIG_HOME")?.ifEmpty { null } ?: "$home/.config"
    // The Flatpak's XDG_CONFIG_HOME is its own, so the host's user-dirs.dirs is looked for as well.
    val named =
        listOf(File(configHome, USER_DIRS), File(home, ".config/$USER_DIRS"))
            .firstNotNullOfOrNull { file ->
                runCatching { file.takeIf(File::isFile)?.readLines() }
                    .getOrNull()
                    ?.firstNotNullOfOrNull { line -> XDG_DOCUMENTS.matchEntire(line.trim())?.groupValues?.get(1) }
            }?.replace(HOME_VARIABLE, home)
    // An entry naming the home folder itself means the user has no documents folder of its own.
    return named?.takeIf { File(it) != File(home) }?.let(::File) ?: File(home, "Documents")
}

private const val USER_DIRS = "user-dirs.dirs"

/** How `user-dirs.dirs` writes the home folder. */
private const val HOME_VARIABLE = "\$HOME"

/** `XDG_DOCUMENTS_DIR="$HOME/Documents"`, as `xdg-user-dirs-update` writes it. */
private val XDG_DOCUMENTS = Regex("""XDG_DOCUMENTS_DIR="(.*)"""")

actual fun cloudLibrariesDir(): String = File(PlatformDirs.appDataDir(), CLOUD_LIBRARIES_DIR).path

/** Desktop asks for no storage grant, so nothing is exempt from one. */
actual fun isAppPrivateLocation(location: String): Boolean = false

/** The folder under the app's data directory that holds the cloud libraries' working copies. */
private const val CLOUD_LIBRARIES_DIR = "cloud-libraries"

actual fun supportsSharing(): Boolean = false

/** The window takes files dropped on it ([dev.npn.app.ui.common.DesktopBridge.droppedFiles]). */
actual fun acceptsFileDrops(): Boolean = true

/** Flatpak sets FLATPAK_ID inside the sandbox; Flathub ships the updates there. */
actual fun checksForUpdates(): Boolean = System.getenv("FLATPAK_ID").isNullOrBlank()

actual suspend fun createLibraryFolder(
    parentLocation: String,
    name: String,
): String? = LocalLibraryFolders.create(parentLocation, name)

actual suspend fun sharePdfBytes(
    fileName: String,
    bytes: ByteArray,
    io: CoroutineDispatcher,
) {
    error("Sharing is not supported on desktop; export via the save-file dialog instead.")
}

actual suspend fun copyToClipboard(text: String) {
    val selection = StringSelection(text)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
}

/** The settings file a forgotten PIN is reset from, while the app is closed. */
actual fun appLockRecoveryFile(): String? = SettingsRepositoryImpl.defaultFile().path

actual fun focusesSecretFieldOnEntry(): Boolean = true
