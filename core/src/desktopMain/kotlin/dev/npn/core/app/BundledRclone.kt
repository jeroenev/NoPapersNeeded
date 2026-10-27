package dev.npn.core.app

import java.io.File

/**
 * The rclone executable a packaged desktop build ships, or null when it ships none and rclone has
 * to come from the user's `PATH`. Resolved once per host and cached, in this order:
 *
 * 1. `-Dnpn.rclone=…` or `NPN_RCLONE=…` — an explicit override; the Gradle `run`/`hotRun` tasks set
 *    the former to the engine they download, and the live rclone tests use the latter;
 * 2. the app resources of a packaged build (`compose.application.resources.dir`), where every
 *    package carries `rclone/rclone` (`rclone.exe` on Windows; `stageAppResources` in
 *    `desktopApp/build.gradle.kts`);
 * 3. a source checkout's `desktopApp/build/rclone/rclone`, so `mise hot` / `mise desktop` find the
 *    engine without a packaged build.
 *
 * The Android side is different: it execs the `librclone.so` the APK ships in its native library
 * directory, which only Android may execute, so that host resolves its own path.
 */
fun bundledRcloneBinary(): String? = BundledRclone.resolve()?.absolutePath

private object BundledRclone {
    private val resolved: File? by lazy { resolveUncached() }

    fun resolve(): File? = resolved

    private fun resolveUncached(): File? = override() ?: inAppResources() ?: fromSourceCheckout()

    /** An explicit executable from a flag or the environment; blank and non-executables are ignored. */
    private fun override(): File? {
        for (value in listOf(System.getProperty("npn.rclone"), System.getenv("NPN_RCLONE"))) {
            val path = value?.takeIf { it.isNotBlank() } ?: continue
            executable(File(path))?.let { return it }
        }
        return null
    }

    /** `rclone/rclone` in the app resources Compose copies into a packaged app image, on every OS alike. */
    private fun inAppResources(): File? {
        val resources = System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }
        return resources?.let { executable(File(it, "rclone/${executableName()}")) }
    }

    /**
     * The engine a source checkout downloaded into `desktopApp/build/rclone/`, found by walking up a
     * few levels from the working directory so `mise hot` (project dir) and `mise desktop` both
     * resolve it. A packaged app never has these paths, so this cannot shadow the bundled binary.
     */
    private fun fromSourceCheckout(): File? {
        val executable = executableName()
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(SEARCH_DEPTH) {
            executable(File(dir, "desktopApp/build/rclone/$executable"))?.let { found -> return found }
            executable(File(dir, "build/rclone/$executable"))?.let { found -> return found }
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun executable(file: File): File? = file.takeIf { it.isFile && it.canExecute() }

    private fun executableName(): String = if (isWindows()) "rclone.exe" else "rclone"

    private fun isWindows(): Boolean = osName().contains("win")

    private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()

    /** How many parent directories [fromSourceCheckout] checks before giving up. */
    private const val SEARCH_DEPTH = 3
}
