package dev.npn.app.ui.rules

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
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

    /** The form judges a regex as the matcher will run it: trimmed, as saving trims it. */
    @Test
    fun aRegexIsJudgedTrimmedAsTheMatcherReadsIt() {
        val regex = RuleFormState(name = "n", algorithm = MatchAlgorithm.REGEX, pattern = " inv(oice)? ")
        regex.patternValid shouldBe true
        // An escaped trailing space compiles as typed, but trimming leaves a dangling backslash.
        regex.copy(pattern = "total\\ ").patternValid shouldBe false
    }

    /**
     * A set value is kept as its field's editor reads it: canonical when it fits, as typed and
     * refused when it does not, and a rule with one that does not fit is not saved.
     */
    @Test
    fun aSetValueThatDoesNotFitItsFieldBlocksTheSave() {
        val fields = listOf(def("docDate", FieldType.DATE), def("amount", FieldType.DECIMAL))
        val form = RuleFormState(name = "n", pattern = "p")

        val refused = form.withSet("docDate", FieldParse.Invalid("01/03/2024"))
        refused.sets shouldBe mapOf("docDate" to "01/03/2024")
        refused.setsFit(fields) shouldBe false

        val fixed = refused.withSet("docDate", FieldParse.Ok("2024-03-01")).withSet("amount", FieldParse.Ok("1.25"))
        fixed.refused shouldBe emptySet()
        fixed.setsFit(fields) shouldBe true
        fixed.toRule().sets shouldBe mapOf("docDate" to "2024-03-01", "amount" to "1.25")

        // Clearing a value drops it.
        fixed.withSet("amount", FieldParse.Ok(null)).sets shouldBe mapOf("docDate" to "2024-03-01")
    }

    /** A rule an older build saved with an off-type value is not saved again as it is. */
    @Test
    fun anOffTypeValueStoredEarlierBlocksTheSave() {
        val fields = listOf(def("docDate", FieldType.DATE))
        val form = RuleFormState(name = "n", pattern = "p", sets = mapOf("docDate" to "soon"))

        form.setsFit(fields) shouldBe false
        form.copy(sets = mapOf("docDate" to "2024-03-01")).setsFit(fields) shouldBe true
    }

    private fun def(
        key: String,
        type: FieldType,
    ) = FieldDef(key = key, label = key, type = type, showByDefault = true, aiEnabled = false, position = 0)
}
