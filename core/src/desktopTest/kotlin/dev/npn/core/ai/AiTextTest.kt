package dev.npn.core.ai

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AiTextTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun extractDocRefsFindsValidHexRef() {
        AiText.extractDocRefs("see [doc:$hashA] here") shouldContainExactly listOf(hashA)
    }

    @Test
    fun extractDocRefsLowercasesHash() {
        val upper = "A".repeat(64)
        AiText.extractDocRefs("[doc:$upper]") shouldContainExactly listOf("a".repeat(64))
    }

    @Test
    fun extractDocRefsDedupesPreservingFirstOccurrenceOrder() {
        val text = "[doc:$hashB] then [doc:$hashA] and again [doc:$hashB]"
        AiText.extractDocRefs(text) shouldContainExactly listOf(hashB, hashA)
    }

    @Test
    fun extractDocRefsIgnoresShortOrInvalidHashesAndPlainBrackets() {
        AiText.extractDocRefs("[doc:abc] [see above] plain [brackets]") shouldContainExactly emptyList()
    }

    @Test
    fun trimHistoryKeepsAllTurnsUnderBudget() {
        val history =
            listOf(
                AiChatTurn(AiRole.USER, "hi"),
                AiChatTurn(AiRole.ASSISTANT, "hello"),
            )
        val trimmed = AiText.trimHistory(history, budgetChars = 1_000)
        trimmed.turns shouldContainExactly history
        trimmed.omittedEarlier shouldBe false
    }

    @Test
    fun trimHistoryDropsOldestTurnsFirstAndFlagsOmission() {
        val history =
            listOf(
                AiChatTurn(AiRole.USER, "aaaa"),
                AiChatTurn(AiRole.ASSISTANT, "bbbb"),
                AiChatTurn(AiRole.USER, "cc"),
            )
        // Budget fits the newest two turns (4+2 chars) but not the oldest.
        val trimmed = AiText.trimHistory(history, budgetChars = 6)
        trimmed.turns shouldContainExactly history.drop(1)
        trimmed.omittedEarlier shouldBe true
    }

    @Test
    fun trimHistoryKeepsNewestTurnEvenWhenItAloneExceedsBudget() {
        val history = listOf(AiChatTurn(AiRole.USER, "way too long"))
        val trimmed = AiText.trimHistory(history, budgetChars = 1)
        trimmed.turns shouldContainExactly history
        trimmed.omittedEarlier shouldBe false
    }

    @Test
    fun trimHistoryEmptyIsEmptyAndNotOmitted() {
        val trimmed = AiText.trimHistory(emptyList(), budgetChars = 10)
        trimmed.turns shouldContainExactly emptyList()
        trimmed.omittedEarlier shouldBe false
    }

    @Test
    fun parseTagSuggestionsPlainJsonArray() {
        AiText.parseTagSuggestions("[\"tax\", \"invoice\"]", emptyList()) shouldContainExactly
            listOf("tax", "invoice")
    }

    @Test
    fun parseTagSuggestionsArrayWrappedInProseAndCodeFences() {
        val response = "Here you go:\n```json\n[\"tax\", \"invoice\"]\n```"
        AiText.parseTagSuggestions(response, emptyList()) shouldContainExactly listOf("tax", "invoice")
    }

    @Test
    fun parseTagSuggestionsFiltersAlreadyPresentTagsCaseInsensitively() {
        AiText.parseTagSuggestions("[\"Tax\", \"invoice\"]", listOf("TAX")) shouldContainExactly
            listOf("invoice")
    }

    @Test
    fun parseTagSuggestionsCaseInsensitiveDedupeWithinSuggestions() {
        AiText.parseTagSuggestions("[\"tax\", \"Tax\", \"TAX\"]", emptyList()) shouldContainExactly
            listOf("tax")
    }

    @Test
    fun parseTagSuggestionsDropsBlankEntries() {
        AiText.parseTagSuggestions("[\"\", \"  \", \"invoice\"]", emptyList()) shouldContainExactly
            listOf("invoice")
    }

    @Test
    fun parseTagSuggestionsDoesNotCap() {
        val many = (1..20).joinToString(", ", prefix = "[", postfix = "]") { "\"tag$it\"" }
        AiText.parseTagSuggestions(many, emptyList()).size shouldBe 20
    }

    @Test
    fun parseTagSuggestionsNonJsonReturnsEmpty() {
        AiText.parseTagSuggestions("no arrays here, sorry", emptyList()) shouldContainExactly
            emptyList()
    }

    @Test
    fun parseTagSuggestionsSkipsNonArrayBracketAndFindsRealArray() {
        AiText.parseTagSuggestions("[see above] then [\"tax\", \"invoice\"]", emptyList()) shouldContainExactly
            listOf("tax", "invoice")
    }
}

/** Parsing/validation behavior for [AiText.parseMetadataSuggestion]. */
class ParseMetadataSuggestionTest {
    private fun parse(
        response: String,
        currentTitle: String? = null,
        currentCorrespondent: String? = null,
        currentDocDate: String? = null,
        correspondents: List<String> = emptyList(),
    ) = AiText.parseMetadataSuggestion(response, currentTitle, currentCorrespondent, currentDocDate, correspondents)

    @Test
    fun parsesPlainJsonObject() {
        val result = parse("""{"title": "Invoice Jan", "correspondent": "Acme", "date": "2024-01-15"}""")
        result shouldBe MetadataSuggestion(title = "Invoice Jan", correspondent = "Acme", docDate = "2024-01-15")
    }

    @Test
    fun parsesObjectWrappedInProseAndCodeFences() {
        val response = "Sure!\n```json\n{\"title\": \"Invoice Jan\", \"correspondent\": null, \"date\": null}\n```"
        parse(response) shouldBe MetadataSuggestion(title = "Invoice Jan")
    }

    @Test
    fun nullAndMissingFieldsStayNull() {
        parse("""{"title": null}""") shouldBe MetadataSuggestion.EMPTY
    }

    @Test
    fun nonJsonReturnsEmpty() {
        parse("no object here, sorry") shouldBe MetadataSuggestion.EMPTY
    }

    @Test
    fun blankValuesAreDropped() {
        parse("""{"title": "  ", "correspondent": "", "date": " "}""") shouldBe MetadataSuggestion.EMPTY
    }

    @Test
    fun valuesEqualToCurrentAreDropped() {
        val result =
            parse(
                """{"title": "Invoice Jan", "correspondent": "acme", "date": "2024-01-15"}""",
                currentTitle = "Invoice Jan",
                currentCorrespondent = "Acme", // case-insensitive match
                currentDocDate = "2024-01-15",
            )
        result shouldBe MetadataSuggestion.EMPTY
    }

    @Test
    fun correspondentCanonicalizesToExistingSpelling() {
        val result =
            parse(
                """{"correspondent": "acme corp"}""",
                correspondents = listOf("Acme Corp", "Utility Co"),
            )
        result.correspondent shouldBe "Acme Corp"
    }

    @Test
    fun newCorrespondentKeptVerbatim() {
        parse("""{"correspondent": "Fresh Name"}""").correspondent shouldBe "Fresh Name"
    }

    @Test
    fun invalidDatesAreDropped() {
        parse("""{"date": "January 15, 2024"}""").docDate shouldBe null
        parse("""{"date": "2024-13-01"}""").docDate shouldBe null
        parse("""{"date": "2024-01-32"}""").docDate shouldBe null
        parse("""{"date": "2024-1-5"}""").docDate shouldBe null
    }

    @Test
    fun overlongTitleIsDropped() {
        val longTitle = "x".repeat(AiText.MAX_TITLE_SUGGESTION_CHARS + 1)
        parse("""{"title": "$longTitle"}""").title shouldBe null
    }

    @Test
    fun nonStringPrimitiveValuesAreIgnored() {
        parse("""{"title": 42, "correspondent": true, "date": 2024}""") shouldBe MetadataSuggestion.EMPTY
    }

    @Test
    fun skipsNonObjectBracesAndFindsRealObject() {
        val response = "{not json} then {\"title\": \"Invoice Jan\"}"
        parse(response).title shouldBe "Invoice Jan"
    }
}

/** Ranking/canonicalization/cap behavior for [AiText.rankTagSuggestions]. */
class RankTagSuggestionsTest {
    @Test
    fun rankCanonicalizesToExistingSpelling() {
        val result =
            AiText.rankTagSuggestions(
                response = "[\"Invoice\", \"TAX\"]",
                currentTags = emptyList(),
                vocabulary = listOf("invoice", "tax"),
            )
        result shouldContainExactly
            listOf(
                TagSuggestion("invoice", isNew = false),
                TagSuggestion("tax", isNew = false),
            )
    }

    @Test
    fun rankClassifiesNewTagsNotInVocabulary() {
        val result =
            AiText.rankTagSuggestions(
                response = "[\"invoice\", \"utilities\"]",
                currentTags = emptyList(),
                vocabulary = listOf("invoice"),
            )
        result shouldContainExactly
            listOf(
                TagSuggestion("invoice", isNew = false),
                TagSuggestion("utilities", isNew = true),
            )
    }

    @Test
    fun rankPutsExistingBeforeNewPreservingOrderWithinGroup() {
        val result =
            AiText.rankTagSuggestions(
                response = "[\"newA\", \"invoice\", \"newB\", \"tax\"]",
                currentTags = emptyList(),
                vocabulary = listOf("invoice", "tax"),
            )
        result shouldContainExactly
            listOf(
                TagSuggestion("invoice", isNew = false),
                TagSuggestion("tax", isNew = false),
                TagSuggestion("newA", isNew = true),
                TagSuggestion("newB", isNew = true),
            )
    }

    @Test
    fun rankCapsNewTagsAtMaxNew() {
        val result =
            AiText.rankTagSuggestions(
                response = "[\"n1\", \"n2\", \"n3\", \"n4\"]",
                currentTags = emptyList(),
                vocabulary = emptyList(),
                maxNew = 2,
            )
        result shouldContainExactly
            listOf(
                TagSuggestion("n1", isNew = true),
                TagSuggestion("n2", isNew = true),
            )
    }

    @Test
    fun rankUsesDefaultNewCapOfFive() {
        val result =
            AiText.rankTagSuggestions(
                response = "[\"n1\", \"n2\", \"n3\", \"n4\", \"n5\", \"n6\", \"n7\"]",
                currentTags = emptyList(),
                vocabulary = emptyList(),
            )
        result shouldContainExactly
            listOf(
                TagSuggestion("n1", isNew = true),
                TagSuggestion("n2", isNew = true),
                TagSuggestion("n3", isNew = true),
                TagSuggestion("n4", isNew = true),
                TagSuggestion("n5", isNew = true),
            )
        AiText.MAX_NEW_TAGS_PER_DOC shouldBe 5
    }

    @Test
    fun rankRespectsPerDocCap() {
        val result =
            AiText.rankTagSuggestions(
                response = "[\"invoice\", \"tax\", \"year\", \"utilities\"]",
                currentTags = emptyList(),
                vocabulary = listOf("invoice", "tax", "year", "utilities"),
                perDocMax = 2,
            )
        result shouldContainExactly
            listOf(
                TagSuggestion("invoice", isNew = false),
                TagSuggestion("tax", isNew = false),
            )
    }

    @Test
    fun rankNewCapNeverExceedsPerDocMax() {
        val result =
            AiText.rankTagSuggestions(
                response = "[\"n1\", \"n2\", \"n3\"]",
                currentTags = emptyList(),
                vocabulary = emptyList(),
                perDocMax = 1,
            )
        result shouldContainExactly listOf(TagSuggestion("n1", isNew = true))
    }
}
