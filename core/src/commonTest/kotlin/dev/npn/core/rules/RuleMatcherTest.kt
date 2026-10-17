package dev.npn.core.rules

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class RuleMatcherTest {
    private fun rule(
        algorithm: MatchAlgorithm,
        pattern: String,
        field: RuleField = RuleField.Content,
    ) = AutoMatchRule(
        id = "r1",
        name = "test",
        algorithm = algorithm,
        pattern = pattern,
        field = field,
    )

    @Test
    fun exactMatchesSubstringIgnoringCase() {
        RuleMatcher.matches(rule(MatchAlgorithm.EXACT, "INVOICE"), "Monthly invoice") shouldBe true
        RuleMatcher.matches(rule(MatchAlgorithm.EXACT, "receipt"), "Monthly invoice") shouldBe false
    }

    @Test
    fun anyWordMatchesOneWholeWord() {
        RuleMatcher.matches(rule(MatchAlgorithm.ANY_WORD, "invoice receipt"), "The invoice arrived") shouldBe true
        RuleMatcher.matches(rule(MatchAlgorithm.ANY_WORD, "invo receipt"), "The invoice arrived") shouldBe false
    }

    @Test
    fun allWordsMatchesEveryWholeWord() {
        RuleMatcher.matches(rule(MatchAlgorithm.ALL_WORDS, "invoice acme"), "Invoice from ACME") shouldBe true
        RuleMatcher.matches(rule(MatchAlgorithm.ALL_WORDS, "invoice beta"), "Invoice from ACME") shouldBe false
    }

    @Test
    fun regexMatchesPattern() {
        RuleMatcher.matches(rule(MatchAlgorithm.REGEX, "\\binv\\d+"), "inv123 here") shouldBe true
        RuleMatcher.matches(rule(MatchAlgorithm.REGEX, "\\binv\\d+"), "invoice here") shouldBe false
        RuleMatcher.matches(rule(MatchAlgorithm.REGEX, "[invalid"), "text") shouldBe false
    }

    @Test
    fun fuzzyMatchesSimilarSubstring() {
        RuleMatcher.matches(rule(MatchAlgorithm.FUZZY, "electricity"), "electrcty bill") shouldBe true
        RuleMatcher.matches(rule(MatchAlgorithm.FUZZY, "electricity"), "water bill") shouldBe false
    }

    @Test
    fun blankPatternNeverMatches() {
        RuleMatcher.matches(rule(MatchAlgorithm.EXACT, "   "), "text") shouldBe false
        RuleMatcher.matches(rule(MatchAlgorithm.ANY_WORD, ""), "text") shouldBe false
    }
}
