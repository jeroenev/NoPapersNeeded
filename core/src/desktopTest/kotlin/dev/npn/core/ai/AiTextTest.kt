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

    // ---- parseTranscription: page OCR replies ----

    @Test
    fun parseTranscriptionReturnsThePageText() {
        AiText.parseTranscription("Invoice 42\nAcme Corporation") shouldBe "Invoice 42\nAcme Corporation"
    }

    @Test
    fun parseTranscriptionKeepsInternalLineBreaksAndBlankLines() {
        // The text goes verbatim into a write-once sidecar and the search index, so the page's own
        // layout must survive; only the reply's outer whitespace is dropped.
        AiText.parseTranscription("\n  line one\n\nline two  \n") shouldBe "line one\n\nline two"
    }

    @Test
    fun parseTranscriptionUnwrapsACodeFenceTheModelAdded() {
        AiText.parseTranscription("```\nInvoice 42\nAcme\n```") shouldBe "Invoice 42\nAcme"
        AiText.parseTranscription("```text\nInvoice 42\n```") shouldBe "Invoice 42"
    }

    @Test
    fun parseTranscriptionReportsAnExplicitlyBlankPageAsEmptyText() {
        // "" is a definitive answer, not a failure: retrying would only buy the same one again.
        AiText.parseTranscription(AiText.BLANK_PAGE_MARKER) shouldBe ""
        AiText.parseTranscription("  [[blank]]  ") shouldBe ""
    }

    @Test
    fun parseTranscriptionReportsAnEmptyReplyAsNoAnswer() {
        // null, not "": the page's text is still unknown, so the caller may retry.
        AiText.parseTranscription("") shouldBe null
        AiText.parseTranscription("   \n  ") shouldBe null
    }

    @Test
    fun parseTranscriptionKeepsAPageWhoseTextMerelyMentionsTheMarker() {
        // Only a reply that is nothing but the marker means "blank"; a page quoting it is content.
        AiText.parseTranscription("The form says ${AiText.BLANK_PAGE_MARKER} here") shouldBe
            "The form says ${AiText.BLANK_PAGE_MARKER} here"
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

/** Parsing/validation behavior for [AiText.parseEditSuggestion]. */
class ParseEditSuggestionTest {
    private fun parse(
        response: String,
        currentTitle: String? = null,
        currentCorrespondent: String? = null,
        currentDocDate: String? = null,
        correspondents: List<String> = emptyList(),
        currentTags: List<String> = emptyList(),
        vocabulary: List<String> = emptyList(),
        fields: List<AiFieldConfig> = emptyList(),
        maxTags: Int = 8,
        maxNewTags: Int = 5,
    ) = AiText.parseEditSuggestion(
        response,
        currentTitle,
        currentCorrespondent,
        currentDocDate,
        correspondents,
        currentTags,
        vocabulary,
        fields,
        maxTags,
        maxNewTags,
    )

    @Test
    fun parsesCombinedObject() {
        val result =
            parse(
                """{"add_tags": ["tax", "invoice"], "remove_tags": ["utility"], """ +
                    """"title": "Invoice Jan", "correspondent": "Acme", "date": "2024-01-15"}""",
                currentTags = listOf("Utility"),
                vocabulary = listOf("tax", "invoice"),
                fields =
                    listOf(
                        AiFieldConfig(AiField.TITLE),
                        AiFieldConfig(AiField.CORRESPONDENT),
                        AiFieldConfig(AiField.DOC_DATE),
                    ),
            )
        result.addTags shouldContainExactly
            listOf(
                TagSuggestion("tax", isNew = false),
                TagSuggestion("invoice", isNew = false),
            )
        result.removeTags shouldContainExactly listOf("Utility")
        result.metadata shouldBe
            MetadataSuggestion(title = "Invoice Jan", correspondent = "Acme", docDate = "2024-01-15")
    }

    @Test
    fun nonJsonReturnsEmpty() {
        parse("no object here") shouldBe AiEditSuggestion.EMPTY
    }

    @Test
    fun removalsRestrictedToCurrentTagsAndCanonicalized() {
        val result =
            parse(
                """{"remove_tags": ["UTILITY", "not-there"]}""",
                currentTags = listOf("Utility", "Invoice"),
            )
        result.removeTags shouldContainExactly listOf("Utility")
    }

    @Test
    fun removalsDedupeCaseInsensitively() {
        val result =
            parse(
                """{"remove_tags": ["utility", "Utility"]}""",
                currentTags = listOf("Utility"),
            )
        result.removeTags shouldContainExactly listOf("Utility")
    }

    @Test
    fun addTagsAcceptTagsAliasAndCapsNew() {
        val result =
            parse(
                """{"tags": ["n1", "n2", "n3"]}""",
                vocabulary = emptyList(),
                maxNewTags = 2,
            )
        result.addTags shouldContainExactly listOf(TagSuggestion("n1", true), TagSuggestion("n2", true))
    }

    @Test
    fun unfilledModeDropsValueWhenCurrentPresent() {
        val result =
            parse(
                """{"title": "New title"}""",
                currentTitle = "Old title",
                fields = listOf(AiFieldConfig(AiField.TITLE, AiFieldMode.UNFILLED)),
            )
        result.metadata shouldBe MetadataSuggestion.EMPTY
    }

    @Test
    fun unfilledModeKeepsValueWhenCurrentBlank() {
        val result =
            parse(
                """{"title": "New title"}""",
                fields = listOf(AiFieldConfig(AiField.TITLE, AiFieldMode.UNFILLED)),
            )
        result.metadata.title shouldBe "New title"
    }

    @Test
    fun allModeAllowsOverwriteSuggestion() {
        val result =
            parse(
                """{"title": "New title"}""",
                currentTitle = "Old title",
                fields = listOf(AiFieldConfig(AiField.TITLE, AiFieldMode.ALL)),
            )
        result.metadata.title shouldBe "New title"
    }

    @Test
    fun unrequestedFieldsAreDroppedEvenWhenModelReturnsThem() {
        val result =
            parse(
                """{"title": "New title", "correspondent": "Acme", "date": "2024-01-15"}""",
                fields = listOf(AiFieldConfig(AiField.CORRESPONDENT)),
            )
        result.metadata shouldBe MetadataSuggestion(correspondent = "Acme")
    }

    @Test
    fun addTagsStillRankExistingBeforeNew() {
        val result =
            parse(
                """{"add_tags": ["newA", "invoice", "newB"]}""",
                vocabulary = listOf("invoice"),
                maxTags = 8,
                maxNewTags = 5,
            )
        result.addTags shouldContainExactly
            listOf(
                TagSuggestion("invoice", isNew = false),
                TagSuggestion("newA", isNew = true),
                TagSuggestion("newB", isNew = true),
            )
    }
}
