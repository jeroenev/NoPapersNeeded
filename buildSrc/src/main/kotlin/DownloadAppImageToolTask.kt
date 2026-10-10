import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Downloads appimagetool or the AppImage runtime, pinned to [sha256]: the release job runs the one and
 * ships the other inside every AppImage.
 */
abstract class DownloadAppImageToolTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun download() {
        val tool = VerifiedDownload.fetch(url.get(), sha256.get(), outFile.get().asFile) { logger.lifecycle(it) }
        tool.setExecutable(true)
    }
}
