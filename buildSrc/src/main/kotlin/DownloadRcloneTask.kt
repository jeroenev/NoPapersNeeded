import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Downloads the official rclone release archive for one platform, pinned to [sha256] (the checksum
 * rclone publishes in its signed `SHA256SUMS`), and extracts its executable to [binary]: the engine
 * the desktop packages ship for cloud sync.
 */
abstract class DownloadRcloneTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    /** rclone's platform name, e.g. `linux-amd64`, `windows-amd64`, `osx-arm64`. */
    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    /** The extracted `rclone` or `rclone.exe`. */
    @get:OutputFile
    abstract val binary: RegularFileProperty

    @TaskAction
    fun download() {
        val archiveName = "rclone-${version.get()}-${platform.get()}.zip"
        val archive =
            VerifiedDownload.fetch(
                "https://downloads.rclone.org/${version.get()}/$archiveName",
                sha256.get(),
                File(temporaryDir, archiveName),
            ) { logger.lifecycle(it) }
        val target = binary.get().asFile
        val executable = target.name
        target.parentFile.mkdirs()
        var found = false
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith("/$executable")) {
                    target.outputStream().use { zip.copyTo(it) }
                    found = true
                }
            }
        }
        if (!found) throw GradleException("rclone executable not found inside $archiveName")
        target.setExecutable(true, false)
    }
}
