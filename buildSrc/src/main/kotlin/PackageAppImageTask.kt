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

        /** The AppDir's desktop entry, which appimagetool requires; its Icon names [appName]. */
        @get:InputFile
        abstract val desktopEntry: RegularFileProperty

        @get:InputFile
        abstract val iconFile: RegularFileProperty

        @get:Input
        abstract val appName: Property<String>

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

            // Copy the self-contained app-image (bundled JRE + launcher) into AppDir/usr, every file
            // with the mode jpackage gave it.
            TreeCopy.keepingModes(src, File(dir, "usr"))

            val launcherFile = File(dir, "usr/bin/$name")
            require(launcherFile.canExecute()) { "no executable launcher at usr/bin/$name inside app-image" }
            val launcher = "usr/bin/$name"

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

            desktopEntry.get().asFile.copyTo(File(dir, "$name.desktop"), overwrite = true)

            // The desktop entry's Icon is "$name", which appimagetool resolves to this PNG at the
            // AppDir root. Same file jpackage uses for the .deb.
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
