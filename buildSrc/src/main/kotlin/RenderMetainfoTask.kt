import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fills in the metainfo's release version and date. The date is the built commit's, which keeps
 * the tarball reproducible.
 */
abstract class RenderMetainfoTask : DefaultTask() {
    @get:InputFile
    abstract val template: RegularFileProperty

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val releaseDate: Property<String>

    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun render() {
        val text =
            template
                .get()
                .asFile
                .readText()
                .replace("@VERSION@", version.get())
                .replace("@DATE@", releaseDate.get())
        outFile
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(text)
    }
}
