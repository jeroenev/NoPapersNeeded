package dev.npn.core.util

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test

/**
 * User data reaches a log message only through [sensitive]. The app's log, and so the diagnostics
 * bundle a user attaches to a public issue, must not carry a document's name or path, a tag, a
 * folder or the library's location.
 *
 * A source scan rather than a type: log messages are strings built by interpolation, so this reads
 * every literal passed to `.warn(`, `.info(`, `.error(` or `.runLogged(` in main sources and fails
 * on an interpolated name that is known to hold user data and is not wrapped in [sensitive].
 * It is a net for the usual names, not a proof; [dev.npn.core.diagnostics.Diagnostics.sanitize]
 * is the backstop behind it.
 */
class LogMessageUserDataTest {
    @Test
    fun logMessagesMarkUserData() {
        val sources = mainSources()
        sources.size shouldBeGreaterThan 100

        val found = sources.flatMap { file -> scan(file.name, file.readText()).map { "${file.path}:$it" } }

        // Each line names the file, the line and the expression to wrap in sensitive(…).
        found.joinToString("\n") shouldBe ""
    }

    @Test
    fun aRawNameIsFoundAndAMarkedOneIsNot() {
        val source =
            """
            log.warn("Importing ${'$'}name failed", it)
            log.runLogged("Reading ${'$'}{doc.relPath}") { read() }
            log.warn("Tagging with ${'$'}{sensitive(tag, Sensitive.Kind.TAG)} failed")
            log.warn("Hashing ${'$'}{sensitive(doc.relPath, Kind.DOCUMENT)} failed")
            log.warn("Deleting the file of ${'$'}{doc.docId} failed")
            log
                .error("Moving ${'$'}{move.from} back failed, ${'$'}{returned.joinToString { it.relPath }}")
            error("Not a journal source: ${'$'}relPath")
            """.trimIndent()

        scan("Example.kt", source) shouldContainExactly
            listOf("1: name", "2: doc.relPath", "7: move.from", "7: returned.joinToString { it.relPath }")
    }

    @Test
    fun anAllowedNameIsNotFound() {
        scan("QueueDrainer.kt", "log.warn(\"${'$'}name drain stopped\")") shouldBe emptyList()
    }

    private fun mainSources(): List<File> {
        val root =
            generateSequence(
                File("").absoluteFile,
            ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        return listOf("core/src", "composeApp/src", "desktopApp/src", "androidApp/src")
            .map { File(root, it) }
            .flatMap { src ->
                src.listFiles().orEmpty().filter {
                    it.isDirectory &&
                        !it.name.contains("test", ignoreCase = true)
                }
            }.flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    /** "line: expression" for each interpolated user-data name in a log call of [source]. */
    private fun scan(
        fileName: String,
        source: String,
    ): List<String> =
        LOG_CALL
            .findAll(source)
            .flatMap { call ->
                val line = source.substring(0, call.range.first).count { it == '\n' } + 1
                interpolations(source, call.range.last)
                    .filter { expr -> !expr.startsWith("sensitive(") && holdsUserData(expr) }
                    .filter { expr -> (fileName to expr) !in ALLOWED }
                    .map { "$line: $it" }
            }.toList()

    /** The expressions interpolated into the string literal whose opening quote is at [quote]. */
    private fun interpolations(
        source: String,
        quote: Int,
    ): List<String> {
        if (source.startsWith("\"\"\"", quote)) return emptyList()
        val found = mutableListOf<String>()
        var i = quote + 1
        while (i < source.length && source[i] != '"' && source[i] != '\n') {
            when {
                source[i] == '\\' -> {
                    i += 2
                }

                source.startsWith("\${", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < source.length && depth > 0) {
                        if (source[j] == '{') depth++
                        if (source[j] == '}') depth--
                        j++
                    }
                    found += source.substring(i + 2, j - 1).trim()
                    i = j
                }

                source[i] == '$' -> {
                    val name = IDENTIFIER.matchAt(source, i + 1)?.value
                    if (name != null) found += name
                    i += 1 + (name?.length ?: 0)
                }

                else -> {
                    i++
                }
            }
        }
        return found
    }

    private fun holdsUserData(expr: String): Boolean =
        expr == "name" ||
            expr == "file.name" ||
            IDENTIFIER.findAll(expr).any { it.value in USER_DATA_NAMES }

    private companion object {
        val LOG_CALL = Regex("\\.(?:warn|info|error|runLogged)\\(\\s*\"")
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Names this codebase gives a document's path or name, a tag, a folder or a location. */
        val USER_DATA_NAMES =
            setOf(
                "relPath",
                "toRelPath",
                "relForError",
                "norm",
                "tmpPath",
                "aside",
                "kept",
                "movedTo",
                "folder",
                "tag",
                "tags",
                "title",
                "correspondent",
                "location",
                "fileName",
                "path",
                "file",
                "item",
                "step",
                "from",
                "to",
            )

        /** A name the scan cannot tell apart from user data, where it is none. */
        val ALLOWED =
            setOf(
                // The drainer's own name ("OCR", "AI enrichment").
                "QueueDrainer.kt" to "name",
                // A journal object's path: a content hash under .npn/journal, written by the app.
                "JournalCoordinator.kt" to "published.relPath",
            )
    }
}
