import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FieldTypeGuardTaskTest {
    private val root: File = createTempDirectory("field-type-guard").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun source(
        path: String,
        text: String,
    ) {
        root.resolve(path).apply { parentFile.mkdirs() }.writeText(text.trimIndent())
    }

    private fun runGuard(allowed: Set<String> = setOf(ALLOWED)) {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        project.tasks
            .register("checkFieldTypeBranches", FieldTypeGuardTask::class.java) {
                sources.from(project.fileTree(root) { include("**/*.kt") })
                rootDirectory.set(root)
                this.allowed.set(allowed)
            }.get()
            .checkFieldTypeBranches()
    }

    private fun problems(allowed: Set<String> = setOf(ALLOWED)): List<String> =
        assertFailsWith<GradleException> { runGuard(allowed) }
            .message
            .orEmpty()
            .lines()
            .filter { it.startsWith("  - ") }
            .map { it.removePrefix("  - ") }

    @Test
    fun anAllowedFileMayBranchAndOthersMayNameAConstantAsAValue() {
        source(
            ALLOWED,
            """
            fun label(type: FieldType) =
                when (type) {
                    FieldType.TEXT, FieldType.LONG_TEXT -> "text"
                    FieldType.DATE -> "date"
                }
            fun decimal(type: FieldType) = type == FieldType.DECIMAL
            """,
        )
        source(
            "core/src/commonMain/kotlin/Seeds.kt",
            """
            val seeds = listOf(FieldDef("title", FieldType.TEXT, position = 0))
            fun typeOf(types: Map<String, FieldType>, key: String) = types[key] ?: FieldType.TEXT
            // A comment may say FieldType.DATE -> anything.
            /** So may a KDoc: `type == FieldType.DATE`. */
            """,
        )
        runGuard()
    }

    @Test
    fun reportsEveryBranchOutsideTheAllowedFiles() {
        source(ALLOWED, "")
        source(
            "core/src/commonMain/kotlin/Branches.kt",
            """
            fun a(type: FieldType) = when (type) {
                FieldType.TEXT, FieldType.LONG_TEXT -> 1
                else -> 2
            }
            fun b(def: FieldDef) = def.type == FieldType.DATE
            fun c(def: FieldDef) = FieldType.NUMBER != def.type
            fun d(value: Any) = value is FieldType
            fun e(value: Any) = value !is FieldType
            """,
        )
        assertEquals(
            listOf(
                "core/src/commonMain/kotlin/Branches.kt:2: FieldType.TEXT, FieldType.LONG_TEXT -> 1",
                "core/src/commonMain/kotlin/Branches.kt:5: fun b(def: FieldDef) = def.type == FieldType.DATE",
                "core/src/commonMain/kotlin/Branches.kt:6: fun c(def: FieldDef) = FieldType.NUMBER != def.type",
                "core/src/commonMain/kotlin/Branches.kt:7: fun d(value: Any) = value is FieldType",
                "core/src/commonMain/kotlin/Branches.kt:8: fun e(value: Any) = value !is FieldType",
            ),
            problems(),
        )
    }

    @Test
    fun anAllowedFileThatIsGoneIsReported() {
        assertEquals(
            listOf("$ALLOWED is allowed to branch on FieldType but is not among the scanned sources"),
            problems(),
        )
    }

    private companion object {
        const val ALLOWED = "core/src/commonMain/kotlin/FieldValues.kt"
    }
}
