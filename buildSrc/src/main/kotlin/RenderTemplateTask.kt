import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes [template] to [outFile] with each `@KEY@` replaced by its value in [tokens]. A token left
 * without a value fails the task, so a template cannot gain a placeholder its callers never fill.
 */
abstract class RenderTemplateTask : DefaultTask() {
    @get:InputFile
    abstract val template: RegularFileProperty

    @get:Input
    abstract val tokens: MapProperty<String, String>

    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun render() {
        val source = template.get().asFile
        val text = tokens.get().entries.fold(source.readText()) { text, (key, value) -> text.replace("@$key@", value) }
        val unfilled = TOKEN.find(text)?.value
        check(unfilled == null) { "${source.name}: no value for $unfilled" }
        outFile
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(text)
    }

    private companion object {
        val TOKEN = Regex("@[A-Z_]+@")
    }
}
