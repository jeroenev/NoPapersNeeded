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

    private fun matches(
        rule: AutoMatchRule,
        text: String?,
    ) = RuleMatcher.compile(rule).matches(text)

    @Test
    fun thePublicMatcherTestsAsTheCompiledRuleDoes() {
        val test = RuleMatcher.matcher(rule(MatchAlgorithm.ANY_WORD, "invoice receipt"))
        test("The invoice arrived") shouldBe true
        test("The invo arrived") shouldBe false
        test(null) shouldBe false
        test("   ") shouldBe false
    }

    @Test
    fun exactMatchesSubstringIgnoringCase() {
        matches(rule(MatchAlgorithm.EXACT, "INVOICE"), "Monthly invoice") shouldBe true
        matches(rule(MatchAlgorithm.EXACT, "receipt"), "Monthly invoice") shouldBe false
    }

    @Test
    fun anyWordMatchesOneWholeWord() {
        matches(rule(MatchAlgorithm.ANY_WORD, "invoice receipt"), "The invoice arrived") shouldBe true
        matches(rule(MatchAlgorithm.ANY_WORD, "invo receipt"), "The invoice arrived") shouldBe false
    }

    @Test
    fun allWordsMatchesEveryWholeWord() {
        matches(rule(MatchAlgorithm.ALL_WORDS, "invoice acme"), "Invoice from ACME") shouldBe true
        matches(rule(MatchAlgorithm.ALL_WORDS, "invoice beta"), "Invoice from ACME") shouldBe false
    }

    @Test
    fun regexMatchesPattern() {
        matches(rule(MatchAlgorithm.REGEX, "\\binv\\d+"), "inv123 here") shouldBe true
        matches(rule(MatchAlgorithm.REGEX, "\\binv\\d+"), "invoice here") shouldBe false
        matches(rule(MatchAlgorithm.REGEX, "[invalid"), "text") shouldBe false
    }

    @Test
    fun fuzzyMatchesSimilarSubstring() {
        matches(rule(MatchAlgorithm.FUZZY, "electricity"), "electrcty bill") shouldBe true
        matches(rule(MatchAlgorithm.FUZZY, "electricity"), "water bill") shouldBe false
    }

    @Test
    fun blankPatternNeverMatches() {
        matches(rule(MatchAlgorithm.EXACT, "   "), "text") shouldBe false
        matches(rule(MatchAlgorithm.ANY_WORD, ""), "text") shouldBe false
    }

    /** A pattern is valid when the matcher can run it: only a regular expression can fail to compile. */
    @Test
    fun onlyARegexThatDoesNotCompileIsInvalid() {
        RuleMatcher.isValidPattern(MatchAlgorithm.REGEX, "inv(oice)?") shouldBe true
        RuleMatcher.isValidPattern(MatchAlgorithm.REGEX, "(") shouldBe false
        RuleMatcher.isValidPattern(MatchAlgorithm.EXACT, "(") shouldBe true
        // Judged trimmed, as compile reads it: the escaped trailing space goes, the backslash dangles.
        RuleMatcher.isValidPattern(MatchAlgorithm.REGEX, "total\\ ") shouldBe false
    }
}
