import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Keeps document fields registry data (AGENTS.md, "Document fields are registry data"): code reads
 * a `FieldDef`'s flags and the capabilities each `FieldType` declares, and only the files in
 * [allowed] (canonical form, and the UI where the type picks a control) branch on the type itself.
 *
 * Flags a `when` branch on a `FieldType` constant (`FieldType.DATE ->`), an equality test against
 * one (`== FieldType.DATE`, `FieldType.DATE !=`) and an `is FieldType` check. Naming a constant as
 * a value, as the seeded definitions and defaults do, is fine.
 */
abstract class FieldTypeGuardTask : DefaultTask() {
    /** The main sources to scan. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** What [allowed] paths are relative to. */
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    /** Files that may branch on the type, relative to [rootDirectory]. Each must exist. */
    @get:Input
    abstract val allowed: SetProperty<String>

    @TaskAction
    fun checkFieldTypeBranches() {
        val root = rootDirectory.get().asFile
        val allowedPaths = allowed.get()
        val files = sources.files.associateBy { it.relativeTo(root).invariantSeparatorsPath }.toSortedMap()
        val missing = (allowedPaths - files.keys).sorted()
        val problems =
            missing.map { "$it is allowed to branch on FieldType but is not among the scanned sources" } +
                files.filterKeys { it !in allowedPaths }.flatMap { (path, file) ->
                    findings(file).map { (line, text) -> "$path:$line: $text" }
                }
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("FieldType guard failed with ${problems.size} problem(s):")
                    problems.forEach { appendLine("  - $it") }
                    append(
                        "Read the FieldDef's flags or a capability the FieldType declares (singleLine, " +
                            "hasVocabulary, ...) instead of branching on the type; see AGENTS.md.",
                    )
                },
            )
        }
    }

    /** Each line, outside comments, that branches on the type, with its line number. */
    private fun findings(file: File): List<Pair<Int, String>> =
        file.readLines().mapIndexedNotNull { index, line ->
            val code = line.substringBefore("//").trim()
            val comment = code.startsWith("*") || code.startsWith("/*")
            if (!comment && BRANCH.containsMatchIn(code)) index + 1 to code else null
        }

    private companion object {
        val BRANCH =
            Regex(
                listOf(
                    """\bFieldType\.[A-Z_]+\s*->""",
                    """[!=]==?\s*FieldType\.""",
                    """\bFieldType\.[A-Z_]+\s*[!=]=""",
                    """\bis\s+FieldType\b""",
                ).joinToString("|"),
            )
    }
}
