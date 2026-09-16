package dev.npn.core.app

import dev.npn.core.settings.PlatformDirs
import java.io.File

/**
 * The rclone executable a packaged desktop build ships, or null when it ships none and rclone has
 * to come from the user's `PATH`. Resolved once per host and cached, in this order:
 *
 * 1. `-Dnpn.rclone=…` or `NPN_RCLONE=…` — an explicit override; the Gradle `run`/`hotRun` tasks set
 *    the former to the engine they download, and the live rclone tests use the latter;
 * 2. the jpackage app image, `<app>/lib/rclone/rclone`, where the deb/AppImage/MSI packaging copies
 *    the downloaded binary next to the launcher;
 * 3. a source checkout's `desktopApp/build/rclone/rclone`, so `mise hot` / `mise desktop` find the
 *    engine without a packaged build;
 * 4. a per-platform archive embedded as a resource in the uber-jar, extracted once into the app data
 *    directory and made executable.
 *
 * The Android side is different: it execs the `librclone.so` the APK ships in its native library
 * directory, which only Android may execute, so that host resolves its own path.
 */
fun bundledRcloneBinary(): String? = BundledRclone.resolve()?.absolutePath

private object BundledRclone {
    private val resolved: File? by lazy { resolveUncached() }

    fun resolve(): File? = resolved

    private fun resolveUncached(): File? {
        override()?.let { return it }
        besideTheLauncher()?.let { return it }
        fromSourceCheckout()?.let { return it }
        return extractedFromResources()
    }

    /** An explicit executable from a flag or the environment; blank and non-executables are ignored. */
    private fun override(): File? {
        for (value in listOf(System.getProperty("npn.rclone"), System.getenv("NPN_RCLONE"))) {
            val path = value?.takeIf { it.isNotBlank() } ?: continue
            executable(File(path))?.let { return it }
        }
        return null
    }

    /**
     * `<app>/lib/rclone/rclone` in a jpackage app image. `jpackage.app-path` names the launcher; the
     * code-source fallback covers hosts (the uber-jar) where that property is absent.
     */
    private fun besideTheLauncher(): File? {
        val appDir = launcherAppDir() ?: return null
        return executable(File(appDir, "lib/rclone/${executableName()}"))
    }

    private fun launcherAppDir(): File? {
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { launcher ->
            return File(launcher).parentFile?.parentFile
        }
        val here =
            runCatching {
                File(
                    BundledRclone::class.java.protectionDomain
                        ?.codeSource
                        ?.location
                        ?.toURI() ?: return null,
                )
            }.getOrNull() ?: return null
        // The uber-jar lives in `<app>/lib/app`; a packaged app image puts it in the same shape.
        return here.parentFile?.parentFile
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

    private fun extractedFromResources(): File? {
        val resource = "/rclone/${platformKey()}/${executableName()}"
        val target = File(PlatformDirs.appDataDir(), "rclone/${platformKey()}/${executableName()}")
        if (target.isFile && target.canExecute()) return target
        val bytes = BundledRclone::class.java.getResourceAsStream(resource) ?: return null
        return runCatching {
            target.parentFile?.mkdirs()
            bytes.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            target.setExecutable(true, false)
            target.takeIf { it.canExecute() }
        }.getOrNull()
    }

    private fun executable(file: File): File? = file.takeIf { it.isFile && it.canExecute() }

    private fun executableName(): String = if (isWindows()) "rclone.exe" else "rclone"

    private fun platformKey(): String {
        val os =
            when {
                isWindows() -> "windows"
                osName().contains("mac") || osName().contains("darwin") -> "macos"
                else -> "linux"
            }
        val arch =
            when (System.getProperty("os.arch").orEmpty().lowercase()) {
                "aarch64", "arm64" -> "arm64"
                else -> "amd64"
            }
        return "$os-$arch"
    }

    private fun isWindows(): Boolean = osName().contains("win")

    private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()

    /** How many parent directories [fromSourceCheckout] checks before giving up. */
    private const val SEARCH_DEPTH = 3
}
