package dev.npn.app.ui.rules

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.recreatable
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.runTest
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
}
