package dev.npn.app

import dev.npn.app.ui.ai.UndoStep
import dev.npn.core.diagnostics.Diagnostics
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

/**
 * A failed Undo step is logged by its string ("A bulk action failed for <step>"), and that log line
 * is a retained entry of the diagnostics bundle: it must not carry the field values it restores.
 */
class UndoStepLogTest {
    @Test
    fun aFailedFieldRestoreLogsFieldNamesButNotTheirValues() {
        val step =
            UndoStep.RestoreFields(
                docId = "a".repeat(64),
                values = mapOf("title" to "Hospital bill Jane Doe", "notes" to "Diagnosis follows", "docDate" to null),
            )

        val bundleLog = Diagnostics.sanitize("2026-11-22T10:00:00Z WARN A bulk action failed for $step")

        bundleLog shouldNotContain "Hospital bill"
        bundleLog shouldNotContain "Diagnosis"
        bundleLog shouldContain "A bulk action failed for RestoreFields"
        bundleLog shouldContain "title"
        bundleLog shouldContain "notes"
    }
}
