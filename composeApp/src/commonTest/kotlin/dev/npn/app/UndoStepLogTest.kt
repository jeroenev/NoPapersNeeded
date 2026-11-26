package dev.npn.app

import dev.npn.app.ui.ai.UndoStep
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * A failed Undo step is logged by its string ("A bulk action failed for <step>"), and that log line
 * reaches the diagnostics bundle: it names what kind of write failed and how big it was, never the
 * tag, the field names or the values it restores.
 */
class UndoStepLogTest {
    private val docId = "a".repeat(64)

    @Test
    fun aFailedFieldRestoreLogsNeitherFieldNamesNorValues() {
        val step =
            UndoStep.RestoreFields(
                docId = docId,
                values = mapOf("title" to "Hospital bill Jane Doe", "notes" to "Diagnosis follows", "docDate" to null),
            )

        step.toString() shouldBe "RestoreFields(docId=$docId, fields=3)"
    }

    @Test
    fun aFailedTagStepLogsNoTagName() {
        UndoStep.AddTag("Divorce", listOf("1", "2")).toString() shouldBe "AddTag(<tag>, documents=2)"
        UndoStep.RemoveTag("Health", listOf("1")).toString() shouldBe "RemoveTag(<tag>, documents=1)"
    }
}
