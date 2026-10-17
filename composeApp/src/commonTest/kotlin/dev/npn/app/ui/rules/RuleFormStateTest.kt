package dev.npn.app.ui.rules

import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The rule form's text turns into the rule it saves, and refuses what could not be saved. */
class RuleFormStateTest {
    @Test
    fun aRuleRoundTripsThroughTheForm() {
        val rule =
            AutoMatchRule(
                id = "r1",
                name = "Power",
                enabled = false,
                field = RuleField.Field("correspondent"),
                algorithm = MatchAlgorithm.ALL_WORDS,
                pattern = "energy bill",
                tags = listOf("utilities", "home"),
                sets = mapOf("docType" to "invoice"),
                position = 3,
            )
        RuleFormState.of(rule).toRule() shouldBe rule
    }

    @Test
    fun savingTrimsAndDropsWhatIsBlank() {
        val form =
            RuleFormState(
                name = "  Bank ",
                pattern = " statement ",
                tags = " a, ,b ,",
                sets = mapOf("docType" to " letter ", "title" to "  "),
                position = "x",
            )
        val rule = form.toRule()
        form.isNew shouldBe true
        rule.id shouldBe ""
        rule.enabled shouldBe true
        rule.name shouldBe "Bank"
        rule.pattern shouldBe "statement"
        rule.tags shouldBe listOf("a", "b")
        rule.sets shouldBe mapOf("docType" to "letter")
        rule.position shouldBe 0
    }

    @Test
    fun aRuleNeedsANameAndAPatternThatCompiles() {
        val valid = RuleFormState(name = "n", pattern = "p")
        valid.canSave shouldBe true
        valid.copy(name = " ").canSave shouldBe false
        valid.copy(pattern = "").canSave shouldBe false
        val regex = valid.copy(algorithm = MatchAlgorithm.REGEX, pattern = "(")
        regex.patternValid shouldBe false
        regex.canSave shouldBe false
        // Only a regex pattern has to compile.
        valid.copy(pattern = "(").canSave shouldBe true
    }
}
