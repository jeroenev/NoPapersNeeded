@file:Suppress("MatchingDeclarationName")

package dev.npn.core.settings

/**
 * Resolves platform app-local data/config directories. On Windows these follow
 * %LOCALAPPDATA%/%APPDATA%; on Unix they follow the XDG Base Directory spec.
 */
object PlatformDirs {
    /** Whether this JVM runs on Windows. */
    val isWindows =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) ?: false

    fun dataHome(): String =
        if (isWindows) {
            System.getenv("LOCALAPPDATA")?.ifEmpty { null }
                ?: (System.getProperty("user.home") + "/AppData/Local")
        } else {
            System.getenv("XDG_DATA_HOME")?.ifEmpty { null }
                ?: (System.getProperty("user.home") + "/.local/share")
        }

    fun configHome(): String =
        if (isWindows) {
            System.getenv("APPDATA")?.ifEmpty { null }
                ?: (System.getProperty("user.home") + "/AppData/Roaming")
        } else {
            System.getenv("XDG_CONFIG_HOME")?.ifEmpty { null }
                ?: (System.getProperty("user.home") + "/.config")
        }
}
