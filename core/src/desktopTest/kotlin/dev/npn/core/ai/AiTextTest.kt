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
    fun renderChatInputWithEmptyHistoryRendersOnlyUserLine() {
        AiText.renderChatInput(emptyList(), "What is this?") shouldBe "User: What is this?"
    }

    @Test
    fun renderChatInputWithHistoryRendersConversation() {
        val history = listOf(
            AiChatTurn(AiRole.USER, "hi"),
            AiChatTurn(AiRole.ASSISTANT, "hello"),
        )
        AiText.renderChatInput(history, "next?") shouldBe
            "Conversation so far:\n" +
            "User: hi\n" +
            "Assistant: hello\n" +
            "\n" +
            "User: next?"
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
    fun parseTagSuggestionsCapsAtMax() {
        val many = (1..20).joinToString(", ", prefix = "[", postfix = "]") { "\"tag$it\"" }
        val result = AiText.parseTagSuggestions(many, emptyList())
        result.size shouldBe AiText.MAX_TAG_SUGGESTIONS
        AiText.MAX_TAG_SUGGESTIONS shouldBe 8
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
