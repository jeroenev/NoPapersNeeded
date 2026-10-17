package dev.npn.core.settings

/**
 * Resolves the app's own data and config directories. On Windows these sit under
 * %LOCALAPPDATA%/%APPDATA%; on Unix they follow the XDG Base Directory spec.
 */
object PlatformDirs {
    /** The app's directory name under the platform data and config homes. */
    private const val APP_DIR = "no-papers-needed"
    /** Whether this JVM runs on Windows. */
    val isWindows =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) ?: false

    /** The app's data directory: every library's own directory, the log and the instance lock. */
    fun appDataDir(): String = "${dataHome()}/$APP_DIR"

    /** The app's config directory, which holds this device's settings. */
    fun appConfigDir(): String = "${configHome()}/$APP_DIR"

    private fun dataHome(): String =
        if (isWindows) {
            System.getenv("LOCALAPPDATA")?.ifEmpty { null }
                ?: (System.getProperty("user.home") + "/AppData/Local")
        } else {
            System.getenv("XDG_DATA_HOME")?.ifEmpty { null }
                ?: (System.getProperty("user.home") + "/.local/share")
        }

    private fun configHome(): String =
        if (isWindows) {
            System.getenv("APPDATA")?.ifEmpty { null }
                ?: (System.getProperty("user.home") + "/AppData/Roaming")
        } else {
            System.getenv("XDG_CONFIG_HOME")?.ifEmpty { null }
                ?: (System.getProperty("user.home") + "/.config")
        }
}
