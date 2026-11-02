package dev.npn.app.ui.rules

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.recreatable
import dev.npn.app.ui.common.uiText
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.automation_rule_preview_count
import no_papers_needed.composeapp.generated.resources.automation_rule_preview_more
import kotlin.test.Test

class AutoMatchRulesViewModelTest : MainDispatcherTest() {
    /** A restored process has the rule form open again, with what was typed into it. */
    @Test
    fun aRestoredProcessKeepsTheOpenRuleFormAndWhatWasTyped() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val screen = recreatable { AutoMatchRulesViewModel(session, it) }
            val typed = RuleFormState(name = "Utilities", pattern = "electricity")
            screen.vm.openDialog(RuleDialog.Form(typed))

            val restored = screen.recreate(this, { combine(it.rules, it.fields, it.dialog, ::Triple) })
            restored.dialog.value shouldBe RuleDialog.Form(typed)
        }

    /** The form says which live documents the rule as typed matches, reading their text as a pass would. */
    @Test
    fun theFormPreviewsTheDocumentsTheRuleMatchesBeforeItIsSaved() =
        runTest(dispatcher) {
            val session = library()
            val vm = AutoMatchRulesViewModel(session)
            val seen = collecting(vm.preview)

            vm.openDialog(RuleDialog.Form(RuleFormState(name = "Power", pattern = "electricity")))
            advanceUntilIdle()

            seen shouldContain RulePreview.Checking
            val matches = vm.preview.value as RulePreview.Matches
            matches.summary shouldBe uiText(Res.plurals.automation_rule_preview_count, 2, 2)
            matches.titles shouldContainExactly listOf("Electricity bill", "Gas and power")
            matches.more shouldBe null
        }

    /** A rule on the file name matches file names, not text; past the first few, the rest are counted. */
    @Test
    fun thePreviewReadsTheFieldTheRuleMatchesOn() =
        runTest(dispatcher) {
            val session = library()
            session.documents.documents.value += (1..6).map { listItem(doc("w$it", fileName = "water-$it.pdf")) }
            val vm = AutoMatchRulesViewModel(session)
            collecting(vm.preview)

            val form =
                RuleFormState(
                    name = "Water",
                    field = RuleField.FileName,
                    algorithm = MatchAlgorithm.EXACT,
                    pattern = "water",
                )
            vm.openDialog(RuleDialog.Form(form))
            advanceUntilIdle()

            val matches = vm.preview.value as RulePreview.Matches
            matches.summary shouldBe uiText(Res.plurals.automation_rule_preview_count, 7, 7)
            matches.titles.size shouldBe 5
            matches.more shouldBe uiText(Res.plurals.automation_rule_preview_more, 2, 2)
        }

    /** Nothing to preview while the pattern is blank or a regular expression that does not compile. */
    @Test
    fun aPatternThatCannotMatchShowsNoPreview() =
        runTest(dispatcher) {
            val vm = AutoMatchRulesViewModel(library())
            collecting(vm.preview)

            vm.openDialog(RuleDialog.Form(RuleFormState(name = "n", pattern = "  ")))
            advanceUntilIdle()
            vm.preview.value shouldBe null

            vm.openDialog(RuleDialog.Form(RuleFormState(name = "n", algorithm = MatchAlgorithm.REGEX, pattern = "(")))
            advanceUntilIdle()
            vm.preview.value shouldBe null
        }

    /** Moving a rule swaps its position with its neighbour's, two ordinary rule saves. */
    @Test
    fun movingARuleSwapsItsPositionWithItsNeighbours() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.rules.rules.value = listOf(rule("a", 0), rule("b", 5), rule("c", 9))
            val vm = AutoMatchRulesViewModel(session)

            vm.move("c", -1)
            advanceUntilIdle()

            session.rules.saved.map { it.id to it.position } shouldContainExactlyInAnyOrder listOf("c" to 5, "b" to 9)
            order(session) shouldContainExactly listOf("a", "c", "b")
        }

    /**
     * Rules that share a position run in id order, which a swap cannot change: the list is numbered
     * afresh, saving only the rules whose position moves.
     */
    @Test
    fun movingAmongTiedPositionsNumbersTheListAfresh() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.rules.rules.value = listOf(rule("a", 0), rule("b", 0), rule("c", 0))
            val vm = AutoMatchRulesViewModel(session)

            vm.move("a", 1)
            advanceUntilIdle()

            session.rules.saved.map { it.id to it.position } shouldContainExactlyInAnyOrder listOf("a" to 1, "c" to 2)
            order(session) shouldContainExactly listOf("b", "a", "c")
        }

    @Test
    fun aMovePastEitherEndSavesNothing() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.rules.rules.value = listOf(rule("a", 0), rule("b", 1))
            val vm = AutoMatchRulesViewModel(session)

            vm.move("a", -1)
            vm.move("b", 1)
            vm.move("gone", 1)
            advanceUntilIdle()

            session.rules.saved shouldBe emptyList()
        }

    /** The list decides a rule's place: a new rule goes last, an edited one stays where it is now. */
    @Test
    fun aNewRuleGoesLastAndAnEditedOneKeepsItsPlace() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.rules.rules.value = listOf(rule("a", 0), rule("b", 5))
            val vm = AutoMatchRulesViewModel(session)

            vm.save(RuleFormState(name = "New", pattern = "p").toRule())
            advanceUntilIdle()
            // A form opened before a move still carries the position it was opened with.
            vm.save(rule("a", 9).copy(name = "Renamed"))
            advanceUntilIdle()

            session.rules.saved.map { it.name to it.position } shouldContainExactly listOf("New" to 6, "Renamed" to 0)
        }

    private fun rule(
        id: String,
        position: Int,
    ) = AutoMatchRule(id = id, name = id, pattern = id, position = position)

    private suspend fun order(session: FakeLibrarySession): List<String> =
        session.rules
            .observeRules()
            .first()
            .map { it.id }

    /** Three documents, two of whose text mentions electricity. */
    private fun library(): FakeLibrarySession {
        val session = FakeLibrarySession()
        session.documents.documents.value =
            listOf(
                listItem(doc("a", title = "Electricity bill")),
                listItem(doc("b", fileName = "water.pdf")),
                listItem(doc("c", title = "Gas and power")),
            )
        session.content.extractedTextByDoc =
            mapOf("a" to "Your electricity usage", "b" to "Water meter reading", "c" to "ELECTRICITY and gas")
        return session
    }
}
