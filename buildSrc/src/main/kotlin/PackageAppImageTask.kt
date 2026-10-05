import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Wraps the jpackage app image in an AppImage, which jpackage cannot emit itself. appimagetool runs
 * with `--appimage-extract-and-run`, so the build host needs no FUSE; the AppImage itself still
 * needs FUSE (or that same flag) to run.
 */
abstract class PackageAppImageTask
    @Inject
    constructor(
        private val execOps: ExecOperations,
    ) : DefaultTask() {
        @get:InputDirectory
        abstract val appImageSrc: DirectoryProperty

        @get:InputFile
        abstract val appImageTool: RegularFileProperty

        @get:InputFile
        abstract val iconFile: RegularFileProperty

        @get:Input
        abstract val appName: Property<String>

        @get:Input
        abstract val displayName: Property<String>

        @get:Input
        abstract val wmClass: Property<String>

        @get:Input
        abstract val arch: Property<String>

        /** AppImage update information; appimagetool embeds it and writes the matching .zsync. */
        @get:Input
        abstract val updateInfo: Property<String>

        @get:Internal
        abstract val appDir: DirectoryProperty

        @get:OutputFile
        abstract val outFile: RegularFileProperty

        @TaskAction
        fun build() {
            val name = appName.get()
            val src = appImageSrc.get().asFile
            require(src.isDirectory) { "jpackage app-image not found at $src" }

            val dir = appDir.get().asFile
            dir.deleteRecursively()
            dir.mkdirs()

            // Copy the self-contained app-image (bundled JRE + launcher) into AppDir/usr.
            // Kotlin's copyRecursively does not preserve the executable bit, so restore it
            // afterwards on the launcher and every ELF/.so under the runtime.
            val usrDir = File(dir, "usr")
            src.copyRecursively(usrDir, overwrite = true)

            val launcherFile = File(dir, "usr/bin/$name")
            require(launcherFile.exists()) { "launcher not found at usr/bin/$name inside app-image" }
            val launcher = "usr/bin/$name"

            // Restore executable permission for the launcher and bundled native binaries.
            usrDir.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                val underBin = f.parentFile?.name == "bin"
                val isNative =
                    f.extension.equals("so", ignoreCase = true) ||
                        f.name.contains(".so.")
                if (underBin || isNative) {
                    f.setExecutable(true, false)
                }
            }

            // AppRun -> the bundled launcher.
            val appRun = File(dir, "AppRun")
            appRun.writeText(
                """
                #!/bin/sh
                HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
                exec "${'$'}HERE/$launcher" "${'$'}@"
                """.trimIndent() + "\n",
            )
            appRun.setExecutable(true)

            // Minimal .desktop entry (required by appimagetool). StartupWMClass lets the
            // shell match the running window to this entry and show the app icon in the
            // taskbar.
            File(dir, "$name.desktop").writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=${displayName.get()}
                Exec=$name
                Icon=$name
                Categories=Office;Utility;
                Terminal=false
                StartupWMClass=${wmClass.get()}
                """.trimIndent() + "\n",
            )

            // Icon: the .desktop entry above points at "$name", which appimagetool resolves
            // to this PNG at the AppDir root. Same file jpackage uses for the .deb.
            iconFile.get().asFile.copyTo(File(dir, "$name.png"), overwrite = true)

            val out = outFile.get().asFile
            out.parentFile.mkdirs()

            logger.lifecycle("Running appimagetool -> ${out.absolutePath}")
            execOps
                .exec {
                    executable = appImageTool.get().asFile.absolutePath
                    // --appimage-extract-and-run avoids needing host FUSE at build time.
                    args =
                        listOf(
                            "--appimage-extract-and-run",
                            "--updateinformation",
                            updateInfo.get(),
                            dir.absolutePath,
                            out.absolutePath,
                        )
                    environment("ARCH", arch.get())
                    // appimagetool writes the .zsync into its working directory.
                    workingDir = out.parentFile
                }.assertNormalExitValue()
            logger.lifecycle("AppImage written: ${out.absolutePath}")
            require(File(out.path + ".zsync").exists()) { "appimagetool wrote no .zsync next to ${out.name}" }
        }
    }
