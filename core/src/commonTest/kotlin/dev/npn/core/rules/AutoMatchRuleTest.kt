package dev.npn.core.rules

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** A rule is stored and matched normalized, however it was typed or whichever build wrote it. */
class AutoMatchRuleTest {
    @Test
    fun normalizingTrimsAndDropsWhatIsBlank() {
        val typed =
            AutoMatchRule(
                id = "r1",
                name = "  Bank ",
                pattern = " statement ",
                tags = listOf(" a", " ", "Smith, John "),
                sets = mapOf("docType" to " letter ", "title" to "  "),
            )

        typed.normalized() shouldBe
            typed.copy(
                name = "Bank",
                pattern = "statement",
                tags = listOf("a", "Smith, John"),
                sets = mapOf("docType" to "letter"),
            )
    }

    /** A list another device's build wrote untrimmed reads as this build would have saved it. */
    @Test
    fun aDecodedListIsNormalized() {
        val stored = """[{"id":"r1","name":" Bank ","pattern":" statement ","tags":[" a ",""],"sets":{"k":" v "}}]"""

        AutoMatchRuleJson.decodeList(stored).single() shouldBe
            AutoMatchRule(id = "r1", name = "Bank", pattern = "statement", tags = listOf("a"), sets = mapOf("k" to "v"))
    }
}
