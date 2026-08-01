import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

abstract class LocalizationGuardTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val defaultResourceDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dutchResourceDirectories: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val uiSourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val hardcodedAllowlist: RegularFileProperty

    @TaskAction
    fun checkLocalization() {
        val problems = mutableListOf<String>()
        val defaults = readResources(defaultResourceDirectories.files, problems)
        val dutch = readResources(dutchResourceDirectories.files, problems)

        val requiredKeys = defaults.filterValues { it.translatable }.keys
        (requiredKeys - dutch.keys).sorted().forEach {
            problems += "Dutch resources are missing ${it.displayName} (defined in ${defaults.getValue(it).file.path})"
        }
        (dutch.keys - defaults.keys).sorted().forEach {
            problems += "Dutch resources contain extra ${it.displayName} (${dutch.getValue(it).file.path})"
        }

        (requiredKeys intersect dutch.keys).sorted().forEach { key ->
            val defaultEntry = defaults.getValue(key)
            val dutchEntry = dutch.getValue(key)
            if (defaultEntry.values.keys != dutchEntry.values.keys) {
                problems +=
                    "Plural quantities differ for ${key.displayName}: " +
                    "default=${defaultEntry.values.keys.sorted()}, Dutch=${dutchEntry.values.keys.sorted()}"
            }
            (defaultEntry.values.keys intersect dutchEntry.values.keys).sorted().forEach { quantity ->
                val defaultPlaceholders = placeholders(defaultEntry.values.getValue(quantity))
                val dutchPlaceholders = placeholders(dutchEntry.values.getValue(quantity))
                if (defaultPlaceholders != dutchPlaceholders) {
                    val suffix = quantity.takeIf { it.isNotEmpty() }?.let { "[$it]" }.orEmpty()
                    problems +=
                        "Placeholder mismatch for ${key.displayName}$suffix: " +
                        "default=$defaultPlaceholders, Dutch=$dutchPlaceholders"
                }
            }
        }

        checkHardcodedComposeStrings(problems)

        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Localization guard failed with ${problems.size} problem(s):")
                    problems.sorted().forEach { appendLine("  - $it") }
                    append("Use Compose resources for user-facing text; update both default and values-nl XML.")
                },
            )
        }
        logger.lifecycle("Localization guard passed: default/Dutch resources and commonMain UI are in sync.")
    }

    private fun readResources(
        directories: Set<File>,
        problems: MutableList<String>,
    ): Map<ResourceKey, ResourceEntry> {
        val resources = mutableMapOf<ResourceKey, ResourceEntry>()
        resourceXmlFiles(directories).forEach { file ->
            val document =
                try {
                    newDocumentBuilderFactory().newDocumentBuilder().parse(file)
                } catch (exception: Exception) {
                    problems += "Cannot parse ${file.path}: ${exception.message}"
                    return@forEach
                }
            val root = document.documentElement
            if (root.tagName != "resources") {
                problems += "Expected <resources> in ${file.path}"
                return@forEach
            }
            childElements(root).filter { it.tagName == "string" || it.tagName == "plurals" }.forEach { element ->
                val name = element.getAttribute("name")
                if (name.isBlank()) {
                    problems += "Unnamed <${element.tagName}> in ${file.path}"
                    return@forEach
                }
                val key = ResourceKey(element.tagName, name)
                val values =
                    if (element.tagName == "string") {
                        mapOf("" to element.textContent)
                    } else {
                        childElements(element)
                            .filter { it.tagName == "item" }
                            .associate { it.getAttribute("quantity") to it.textContent }
                    }
                val entry =
                    ResourceEntry(
                        file = file,
                        translatable = element.getAttribute("translatable") != "false",
                        values = values,
                    )
                val previous = resources.putIfAbsent(key, entry)
                if (previous != null) {
                    problems += "Duplicate ${key.displayName} in ${previous.file.path} and ${file.path}"
                }
            }
        }
        return resources
    }

    private fun checkHardcodedComposeStrings(problems: MutableList<String>) {
        val allowlist =
            hardcodedAllowlist.asFile
                .get()
                .readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        val literal = "(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")"
        val patterns =
            listOf(
                "Text" to Regex("\\b(?:Text|BasicText)\\s*\\(\\s*(?:text\\s*=\\s*)?($literal)"),
                "contentDescription" to Regex("\\bcontentDescription\\s*=\\s*($literal)"),
            )

        uiSourceDirectory.asFile
            .get()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                val source = stripKotlinComments(file.readText())
                patterns.forEach { (kind, pattern) ->
                    pattern.findAll(source).forEach { match ->
                        val rawLiteral = match.groupValues[1]
                        val value = rawLiteral.removeSurrounding("\"\"\"").removeSurrounding("\"")
                        if (value.isNotEmpty() && "$kind=$value" !in allowlist) {
                            val line = source.substring(0, match.range.first).count { it == '\n' } + 1
                            problems +=
                                "${file.path}:$line has hardcoded $kind literal $rawLiteral; " +
                                "use stringResource or document an intentional token in the allowlist"
                        }
                    }
                }
            }
    }

    private fun resourceXmlFiles(directories: Set<File>): List<File> =
        directories
            .flatMap { directory -> directory.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList() }
            .sortedBy { it.path }

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    private fun childElements(parent: Element): List<Element> =
        buildList {
            val children = parent.childNodes
            for (index in 0 until children.length) {
                (children.item(index) as? Element)?.let(::add)
            }
        }

    private fun placeholders(value: String): List<String> {
        var implicitIndex = 0
        return PLACEHOLDER
            .findAll(value)
            .mapNotNull { match ->
                val conversion = match.groupValues[3]
                if (conversion == "%" || conversion.equals("n", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val index = match.groupValues[1].ifEmpty { (++implicitIndex).toString() }
                val datePrefix = match.groupValues[2].lowercase()
                "$index:${datePrefix}${conversion.lowercase()}"
            }.sorted()
            .toList()
    }

    private fun stripKotlinComments(source: String): String {
        val result = StringBuilder(source.length)
        var index = 0
        var blockDepth = 0
        var lineComment = false
        var stringDelimiter: String? = null
        var escaped = false
        while (index < source.length) {
            val delimiter = stringDelimiter
            val nextTwo = source.substring(index, minOf(index + 2, source.length))
            val nextThree = source.substring(index, minOf(index + 3, source.length))
            when {
                lineComment -> {
                    val char = source[index]
                    result.append(if (char == '\n') char else ' ')
                    if (char == '\n') lineComment = false
                    index++
                }

                blockDepth > 0 -> {
                    when (nextTwo) {
                        "/*" -> {
                            result.append("  ")
                            blockDepth++
                            index += 2
                        }

                        "*/" -> {
                            result.append("  ")
                            blockDepth--
                            index += 2
                        }

                        else -> {
                            val char = source[index]
                            result.append(if (char == '\n') char else ' ')
                            index++
                        }
                    }
                }

                delimiter != null -> {
                    if (delimiter == "\"\"\"" && nextThree == delimiter) {
                        result.append(delimiter)
                        index += 3
                        stringDelimiter = null
                    } else {
                        val char = source[index]
                        result.append(char)
                        index++
                        if (delimiter != "\"\"\"") {
                            if (!escaped && char.toString() == delimiter) stringDelimiter = null
                            escaped = !escaped && char == '\\'
                        }
                    }
                }

                nextTwo == "//" -> {
                    result.append("  ")
                    lineComment = true
                    index += 2
                }

                nextTwo == "/*" -> {
                    result.append("  ")
                    blockDepth = 1
                    index += 2
                }

                nextThree == "\"\"\"" -> {
                    result.append(nextThree)
                    stringDelimiter = nextThree
                    index += 3
                }

                source[index] == '"' || source[index] == '\'' -> {
                    stringDelimiter = source[index].toString()
                    escaped = false
                    result.append(source[index])
                    index++
                }

                else -> {
                    result.append(source[index++])
                }
            }
        }
        return result.toString()
    }

    private data class ResourceKey(
        val type: String,
        val name: String,
    ) : Comparable<ResourceKey> {
        val displayName: String = "$type/$name"

        override fun compareTo(other: ResourceKey): Int =
            compareValuesBy(this, other, ResourceKey::type, ResourceKey::name)
    }

    private data class ResourceEntry(
        val file: File,
        val translatable: Boolean,
        val values: Map<String, String>,
    )

    companion object {
        private val PLACEHOLDER = Regex("%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?([tT])?([a-zA-Z%])")
    }
}
