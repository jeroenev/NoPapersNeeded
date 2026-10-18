package dev.npn.core.ai

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test

private val seeds = FieldKeys.seedDefinitions().associateBy { it.key }

/** The seeded field [key] as the model is asked to fill it. */
private fun spec(
    key: String,
    mode: AiFieldMode = AiFieldMode.ALL,
    vocabulary: List<String> = emptyList(),
) = AiFieldSpec(seeds.getValue(key), mode, vocabulary)

class AiTextTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun extractDocRefsFindsValidHexRef() {
        extractDocRefs("see [doc:$hashA] here") shouldContainExactly listOf(hashA)
    }

    @Test
    fun extractDocRefsLowercasesHash() {
        val upper = "A".repeat(64)
        extractDocRefs("[doc:$upper]") shouldContainExactly listOf("a".repeat(64))
    }

    @Test
    fun extractDocRefsDedupesPreservingFirstOccurrenceOrder() {
        val text = "[doc:$hashB] then [doc:$hashA] and again [doc:$hashB]"
        extractDocRefs(text) shouldContainExactly listOf(hashB, hashA)
    }

    @Test
    fun extractDocRefsIgnoresShortOrInvalidHashesAndPlainBrackets() {
        extractDocRefs("[doc:abc] [see above] plain [brackets]") shouldContainExactly emptyList()
    }

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
}

/**
 * Parsing/validation behavior for [AiText.parseFieldSuggestion]: the field half of a reply, judged
 * per requested [AiFieldSpec]. Each case asks for exactly the fields it asserts on, since a run only
 * ever accepts what it asked for. Values now key on the registry key (`docDate`, not `date`).
 */
class ParseFieldSuggestionTest {
    private fun numberField(key: String) =
        AiFieldSpec(
            FieldDef(key, key, FieldType.NUMBER, showByDefault = false, aiEnabled = false, position = 9),
            AiFieldMode.ALL,
        )

    /** Parse [body] as a JSON object and run [AiText.parseFieldSuggestion] over it. */
    private fun parse(
        body: String,
        fields: List<AiFieldSpec>,
        currentFields: Map<String, String> = emptyMap(),
    ): FieldSuggestion = AiText.parseFieldSuggestion(Json.decodeFromString(body), currentFields, fields)

    private val allThree =
        listOf(spec(FieldKeys.TITLE), spec(FieldKeys.CORRESPONDENT), spec(FieldKeys.DOC_DATE))

    @Test
    fun readsEveryRequestedField() {
        val result =
            parse(
                """{"title": "Invoice Jan", "correspondent": "Acme", "docDate": "2024-01-15"}""",
                fields = allThree,
            )
        result shouldBe
            FieldSuggestion(
                mapOf(
                    FieldKeys.TITLE to "Invoice Jan",
                    FieldKeys.CORRESPONDENT to "Acme",
                    FieldKeys.DOC_DATE to "2024-01-15",
                ),
            )
    }

    @Test
    fun nullAndMissingFieldsStayEmpty() {
        parse("""{"title": null}""", fields = listOf(spec(FieldKeys.TITLE))) shouldBe FieldSuggestion.EMPTY
    }

    @Test
    fun blankValuesAreDropped() {
        parse(
            """{"title": "  ", "correspondent": "", "docDate": " "}""",
            fields = allThree,
        ) shouldBe FieldSuggestion.EMPTY
    }

    @Test
    fun valuesEqualToCurrentAreDropped() {
        val result =
            parse(
                """{"title": "Invoice Jan", "correspondent": "acme", "docDate": "2024-01-15"}""",
                fields = allThree,
                currentFields =
                    mapOf(
                        FieldKeys.TITLE to "Invoice Jan",
                        FieldKeys.CORRESPONDENT to "Acme", // case-insensitive match
                        FieldKeys.DOC_DATE to "2024-01-15",
                    ),
            )
        result shouldBe FieldSuggestion.EMPTY
    }

    @Test
    fun textFieldCanonicalizesToExistingVocabularySpelling() {
        val result =
            parse(
                """{"correspondent": "acme corp"}""",
                fields = listOf(spec(FieldKeys.CORRESPONDENT, vocabulary = listOf("ACME Corp", "Utility Co"))),
            )
        result[FieldKeys.CORRESPONDENT] shouldBe "ACME Corp"
    }

    @Test
    fun newTextValueKeptVerbatimWhenNoVocabularyMatches() {
        parse(
            """{"correspondent": "Fresh Name"}""",
            fields = listOf(spec(FieldKeys.CORRESPONDENT, vocabulary = listOf("ACME Corp"))),
        )[FieldKeys.CORRESPONDENT] shouldBe "Fresh Name"
    }

    @Test
    fun invalidDatesAreDropped() {
        val date = listOf(spec(FieldKeys.DOC_DATE))
        parse("""{"docDate": "not a date"}""", date)[FieldKeys.DOC_DATE] shouldBe null
        parse("""{"docDate": "January 15, 2024"}""", date)[FieldKeys.DOC_DATE] shouldBe null
        parse("""{"docDate": "2024-13-01"}""", date)[FieldKeys.DOC_DATE] shouldBe null
        parse("""{"docDate": "2024-01-32"}""", date)[FieldKeys.DOC_DATE] shouldBe null
    }

    @Test
    fun overlongTitleIsDropped() {
        val longTitle = "x".repeat(AiText.MAX_PER_DOCUMENT_CHARS + 1)
        parse("""{"title": "$longTitle"}""", listOf(spec(FieldKeys.TITLE)))[FieldKeys.TITLE] shouldBe null
    }

    @Test
    fun onlyRequestedFieldsAreAccepted() {
        // The reply names three fields, but only correspondent was asked for.
        val result =
            parse(
                """{"title": "New title", "correspondent": "Acme", "docDate": "2024-01-15"}""",
                fields = listOf(spec(FieldKeys.CORRESPONDENT)),
            )
        result shouldBe FieldSuggestion(mapOf(FieldKeys.CORRESPONDENT to "Acme"))
    }

    @Test
    fun numberFieldAcceptsANumberDropsWordsAndReadsTheNestedFieldsObject() {
        val amount = listOf(numberField("amount"))
        // A plain integer answers a NUMBER field; the value is read from the nested "fields" object.
        parse("""{"fields": {"amount": "42"}}""", amount)["amount"] shouldBe "42"
        // A spelled-out number is off-type for a NUMBER field, so it is dropped.
        parse("""{"fields": {"amount": "forty-two"}}""", amount)["amount"] shouldBe null
    }

    @Test
    fun dateFieldDropsAnOffTypeAnswer() {
        parse(
            """{"fields": {"docDate": "not a date"}}""",
            listOf(spec(FieldKeys.DOC_DATE)),
        )[FieldKeys.DOC_DATE] shouldBe
            null
    }

    @Test
    fun textFieldSnapsToVocabularyCaseInsensitively() {
        val result =
            parse(
                """{"fields": {"correspondent": "acme corp"}}""",
                fields = listOf(spec(FieldKeys.CORRESPONDENT, vocabulary = listOf("ACME Corp"))),
            )
        result[FieldKeys.CORRESPONDENT] shouldBe "ACME Corp"
    }
}

/** Parsing/validation behavior for [AiText.parseEditSuggestion]. */
class ParseEditSuggestionTest {
    private fun parse(
        response: String,
        currentFields: Map<String, String> = emptyMap(),
        currentTags: List<String> = emptyList(),
        vocabulary: List<String> = emptyList(),
        fields: List<AiFieldSpec> = emptyList(),
        maxTags: Int = 8,
        maxNewTags: Int = 5,
        tags: Boolean = true,
        tagRemovals: Boolean = true,
    ) = AiText.parseEditSuggestion(
        response,
        AiEditRequest(tags = tags, tagRemovals = tagRemovals),
        TagCaps(max = maxTags, maxNew = maxNewTags),
        currentFields,
        fields,
        currentTags,
        vocabulary,
    )

    @Test
    fun parsesCombinedObject() {
        val result =
            parse(
                """{"add_tags": ["tax", "invoice"], "remove_tags": ["utility"], """ +
                    """"fields": {"title": "Invoice Jan", "correspondent": "Acme", "docDate": "2024-01-15"}}""",
                currentTags = listOf("Utility"),
                vocabulary = listOf("tax", "invoice"),
                fields =
                    listOf(
                        spec(FieldKeys.TITLE),
                        spec(FieldKeys.CORRESPONDENT),
                        spec(FieldKeys.DOC_DATE),
                    ),
            )
        result.addTags shouldContainExactly
            listOf(
                TagSuggestion("tax", isNew = false),
                TagSuggestion("invoice", isNew = false),
            )
        result.removeTags shouldContainExactly listOf("Utility")
        result.metadata shouldBe
            FieldSuggestion(
                mapOf(
                    FieldKeys.TITLE to "Invoice Jan",
                    FieldKeys.CORRESPONDENT to "Acme",
                    FieldKeys.DOC_DATE to "2024-01-15",
                ),
            )
    }

    /** A run that asked for no tag changes gets none, whatever the model answers: only its fields. */
    @Test
    fun tagChangesTheRequestDidNotAskForAreDropped() {
        val response = """{"add_tags": ["invoice"], "remove_tags": ["utility"], "fields": {"title": "Invoice Jan"}}"""

        val fieldsOnly =
            parse(
                response,
                currentTags = listOf("utility"),
                vocabulary = listOf("invoice"),
                fields = listOf(spec(FieldKeys.TITLE)),
                tags = false,
                tagRemovals = false,
            )
        val additionsOnly =
            parse(response, currentTags = listOf("utility"), vocabulary = listOf("invoice"), tagRemovals = false)

        fieldsOnly shouldBe AiEditSuggestion(metadata = FieldSuggestion(mapOf(FieldKeys.TITLE to "Invoice Jan")))
        additionsOnly.addTags shouldContainExactly listOf(TagSuggestion("invoice", isNew = false))
        additionsOnly.removeTags shouldBe emptyList()
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
                """{"fields": {"title": "New title"}}""",
                currentFields = mapOf(FieldKeys.TITLE to "Old title"),
                fields = listOf(spec(FieldKeys.TITLE, AiFieldMode.UNFILLED)),
            )
        result.metadata shouldBe FieldSuggestion.EMPTY
    }

    @Test
    fun unfilledModeKeepsValueWhenCurrentBlank() {
        val result =
            parse(
                """{"fields": {"title": "New title"}}""",
                fields = listOf(spec(FieldKeys.TITLE, AiFieldMode.UNFILLED)),
            )
        result.metadata[FieldKeys.TITLE] shouldBe "New title"
    }

    @Test
    fun allModeAllowsOverwriteSuggestion() {
        val result =
            parse(
                """{"fields": {"title": "New title"}}""",
                currentFields = mapOf(FieldKeys.TITLE to "Old title"),
                fields = listOf(spec(FieldKeys.TITLE, AiFieldMode.ALL)),
            )
        result.metadata[FieldKeys.TITLE] shouldBe "New title"
    }

    @Test
    fun unrequestedFieldsAreDroppedEvenWhenModelReturnsThem() {
        val result =
            parse(
                """{"fields": {"title": "New title", "correspondent": "Acme", "docDate": "2024-01-15"}}""",
                fields = listOf(spec(FieldKeys.CORRESPONDENT)),
            )
        result.metadata shouldBe FieldSuggestion(mapOf(FieldKeys.CORRESPONDENT to "Acme"))
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
