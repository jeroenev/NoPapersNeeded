import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Stages the Tesseract a Windows build bundles: [files] taken from the UB Mannheim installer and
 * the [models], under `windows/tesseract` in [outDir], the app resources Compose copies into the
 * app image.
 *
 * Extracting the NSIS installer needs 7-Zip (on PATH, or its default install location; GitHub's
 * Windows runners have it). The installer's DLLs carry ~100 MB of debug info, stripped when a
 * binutils `strip` is around (the runners' MinGW has one); without it the build still works, the
 * installer is just larger.
 */
abstract class PrepareWindowsTesseractTask
    @Inject
    constructor(
        private val execOps: ExecOperations,
    ) : DefaultTask() {
        @get:Input
        abstract val installerUrl: Property<String>

        @get:Input
        abstract val installerSha256: Property<String>

        /** File names to take from the installer. */
        @get:Input
        abstract val files: ListProperty<String>

        @get:Input
        abstract val modelBaseUrl: Property<String>

        /** Tesseract language code to the SHA-256 of its model. */
        @get:Input
        abstract val models: MapProperty<String, String>

        /** Verified downloads, kept across runs so a rebuild does not fetch them again. */
        @get:Internal
        abstract val downloadDir: DirectoryProperty

        @get:OutputDirectory
        abstract val outDir: DirectoryProperty

        @TaskAction
        fun prepare() {
            val downloads = downloadDir.get().asFile.apply { mkdirs() }
            val installerName = installerUrl.get().substringAfterLast('/')
            val installer = fetch(installerUrl.get(), installerSha256.get(), File(downloads, installerName))

            val root = outDir.get().asFile
            root.deleteRecursively()
            val home = File(root, "windows/tesseract").apply { mkdirs() }
            // NSIS installers are 7-Zip archives; `e` takes just the named files, flattened.
            execOps
                .exec {
                    executable = sevenZip()
                    args = listOf("e", "-y", "-o${home.absolutePath}", installer.absolutePath) + files.get()
                }.assertNormalExitValue()
            files.get().forEach { require(File(home, it).isFile) { "$it is not in $installerName" } }
            stripDebugInfo(home)

            val tessdata = File(home, "tessdata").apply { mkdirs() }
            models.get().forEach { (lang, sha256) ->
                val model =
                    fetch("${modelBaseUrl.get()}/$lang.traineddata", sha256, File(downloads, "$lang.traineddata"))
                model.copyTo(File(tessdata, model.name), overwrite = true)
            }
        }

        private fun fetch(
            url: String,
            sha256: String,
            target: File,
        ): File = VerifiedDownload.fetch(url, sha256, target) { logger.lifecycle(it) }

        private fun sevenZip(): String =
            (onPath("7z.exe", "7z", "7zz") ?: File("C:/Program Files/7-Zip/7z.exe").takeIf { it.isFile })
                ?.absolutePath
                ?: throw GradleException("Bundling Tesseract needs 7-Zip (7z) on PATH to unpack its installer")

        private fun stripDebugInfo(dir: File) {
            val strip =
                onPath("strip.exe", "strip")
                    ?: listOf("C:/mingw64/bin/strip.exe", "C:/msys64/mingw64/bin/strip.exe")
                        .map(::File)
                        .firstOrNull { it.isFile }
            if (strip == null) {
                logger.warn("No `strip` found: bundling Tesseract with its debug info (~100 MB more)")
                return
            }
            execOps
                .exec {
                    executable = strip.absolutePath
                    args = listOf("--strip-debug") + files.get().map { File(dir, it).absolutePath }
                }.assertNormalExitValue()
        }

        private fun onPath(vararg names: String): File? =
            System
                .getenv("PATH")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .flatMap { dir -> names.map { File(dir, it) } }
                .firstOrNull { it.isFile && it.canExecute() }
    }
