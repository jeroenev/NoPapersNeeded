package dev.npn.core.rules

import io.kotest.assertions.throwables.shouldThrow
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

    /**
     * A list holding a key a newer build added does not decode: matched without it, the rule would act
     * otherwise than its writer meant, and any edit here would write the list back without it.
     */
    @Test
    fun aListWithAKeyThisBuildDoesNotKnowDoesNotDecode() {
        val newer = """[{"id":"r1","name":"Bank","pattern":"statement","caseSensitive":true}]"""

        shouldThrow<IllegalArgumentException> { AutoMatchRuleJson.decodeList(newer) }
    }

    /** A newer build's option that no rule uses changes nothing. */
    @Test
    fun anUnusedNewKeyStillDecodes() {
        val newer = """[{"id":"r1","name":"Bank","pattern":"statement","caseSensitive":false,"exclude":[]}]"""

        AutoMatchRuleJson.decodeList(newer).single().id shouldBe "r1"
    }

    /** The pre-registry shape still reads, its correspondent and type moved into the set values. */
    @Test
    fun theLegacyShapeStillDecodes() {
        val legacy = """[{"id":"r1","name":"Bank","pattern":"statement","correspondent":"ACME","docType":null}]"""

        AutoMatchRuleJson.decodeList(legacy).single().sets shouldBe mapOf("correspondent" to "ACME")
    }
}
