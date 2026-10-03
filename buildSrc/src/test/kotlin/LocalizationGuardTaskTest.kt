import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalizationGuardTaskTest {
    private val root: File = createTempDirectory("localization-guard").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun strings(
        directory: String,
        vararg entries: String,
    ) {
        root.resolve("res/$directory").mkdirs()
        root.resolve("res/$directory/strings.xml").writeText(
            "<resources>\n${entries.joinToString("\n")}\n</resources>\n",
        )
    }

    /** A default plus two translations that agree on every key and placeholder. */
    private fun consistentResources() {
        strings(
            "values",
            """<string name="greeting">Hello %1${'$'}s</string>""",
            """<plurals name="docs"><item quantity="one">One document</item>""" +
                """<item quantity="other">%1${'$'}d documents</item></plurals>""",
        )
        strings(
            "values-nl",
            """<string name="greeting">Hallo %1${'$'}s</string>""",
            """<plurals name="docs"><item quantity="one">Eén document</item>""" +
                """<item quantity="other">%1${'$'}d documenten</item></plurals>""",
        )
        strings(
            "values-de",
            """<string name="greeting">Hallo %1${'$'}s</string>""",
            """<plurals name="docs"><item quantity="one">Ein Dokument</item>""" +
                """<item quantity="other">%1${'$'}d Dokumente</item></plurals>""",
        )
    }

    private fun runGuard() {
        root.resolve("ui").mkdirs()
        root.resolve("allowlist.txt").writeText("")
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task =
            project.tasks
                .register("checkLocalization", LocalizationGuardTask::class.java) {
                    resourceRoots.from(root.resolve("res"))
                    uiSourceDirectory.set(root.resolve("ui"))
                    hardcodedAllowlist.set(root.resolve("allowlist.txt"))
                }.get()
        task.checkLocalization()
    }

    private fun problems(): List<String> =
        assertFailsWith<GradleException> { runGuard() }
            .message
            .orEmpty()
            .lines()
            .filter { it.startsWith("  - ") }

    @Test
    fun `passes when every translation matches the defaults`() {
        consistentResources()
        runGuard()
    }

    @Test
    fun `reports a key missing from a translation other than Dutch`() {
        consistentResources()
        strings(
            "values-de",
            """<plurals name="docs"><item quantity="one">Ein Dokument</item>""" +
                """<item quantity="other">%1${'$'}d Dokumente</item></plurals>""",
        )

        val problems = problems()

        assertEquals(1, problems.size, problems.joinToString("\n"))
        assertContains(problems.single(), "values-de is missing string/greeting")
    }

    @Test
    fun `reports a mismatched placeholder in a translation other than Dutch`() {
        consistentResources()
        strings(
            "values-de",
            """<string name="greeting">Hallo %1${'$'}d</string>""",
            """<plurals name="docs"><item quantity="one">Ein Dokument</item>""" +
                """<item quantity="other">Dokumente</item></plurals>""",
        )

        val problems = problems()

        assertEquals(2, problems.size, problems.joinToString("\n"))
        assertContains(problems[0], "Placeholder mismatch for plurals/docs[other] in values-de")
        assertContains(problems[1], "Placeholder mismatch for string/greeting in values-de")
    }

    @Test
    fun `ignores qualified directories that are not languages`() {
        consistentResources()
        strings("values-night", """<string name="unrelated">Dark</string>""")

        runGuard()
    }
}
