package dev.npn.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Pure text helpers for the AI layer, split out so they are unit-testable without a model. */
object AiText {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val docRefRegex = Regex("\\[doc:([0-9a-fA-F]{64})]")

    const val MAX_TAG_SUGGESTIONS = 8

    /** Full docIds cited as `[doc:<hash>]` in an assistant answer, first occurrence order. */
    fun extractDocRefs(text: String): List<String> =
        docRefRegex.findAll(text).map { it.groupValues[1].lowercase() }.distinct().toList()

    /** Render a conversation into the single input string the agent run receives. */
    fun renderChatInput(history: List<AiChatTurn>, question: String): String = buildString {
        if (history.isNotEmpty()) {
            appendLine("Conversation so far:")
            for (turn in history) {
                val label = if (turn.role == AiRole.USER) "User" else "Assistant"
                appendLine("$label: ${turn.text}")
            }
            appendLine()
        }
        append("User: ").append(question)
    }

    /**
     * Parse tag suggestions out of a model response: the first JSON string array found wins
     * (models often wrap it in prose or code fences). Suggestions already on the document and
     * case-insensitive duplicates are dropped.
     */
    fun parseTagSuggestions(response: String, currentTags: List<String>): List<String> {
        val current = currentTags.map { it.lowercase() }.toHashSet()
        return firstStringArray(response)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in current }
            .distinctBy { it.lowercase() }
            .take(MAX_TAG_SUGGESTIONS)
    }

    private fun firstStringArray(text: String): List<String> {
        var start = text.indexOf('[')
        while (start >= 0) {
            val end = text.indexOf(']', startIndex = start)
            if (end < 0) return emptyList()
            val candidate = text.substring(start, end + 1)
            val parsed = runCatching {
                json.decodeFromString<JsonArray>(candidate).map { it.jsonPrimitive.content }
            }.getOrNull()
            if (parsed != null) return parsed
            start = text.indexOf('[', startIndex = start + 1)
        }
        return emptyList()
    }
}
