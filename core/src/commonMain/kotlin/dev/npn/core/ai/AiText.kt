package dev.npn.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Result of budget-trimming a conversation: the [turns] to send (oldest-first) and whether any
 * older turns were [omittedEarlier] so the prompt can add a "earlier conversation omitted" note.
 */
data class TrimmedHistory(val turns: List<AiChatTurn>, val omittedEarlier: Boolean)

/** Pure text helpers for the AI layer, split out so they are unit-testable without a model. */
object AiText {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val docRefRegex = Regex("\\[doc:([0-9a-fA-F]{64})]")

    const val MAX_TAG_SUGGESTIONS = 8

    /** Hard cap on genuinely-new (non-vocabulary) tags proposed per document, ranked last. */
    const val MAX_NEW_TAGS_PER_DOC = 2

    /**
     * Character budget for conversation history sent as real user/assistant messages. Derived
     * from the default `contextLength = 128_000` tokens at ~3 chars/token, reserving room for the
     * system prompt, tool schemas, and the answer. Large enough that normal chats never trigger
     * trimming; it exists only to bound pathological growth without an arbitrary turn-count cliff.
     */
    const val CHAT_HISTORY_CHAR_BUDGET = 200_000

    /** Full docIds cited as `[doc:<hash>]` in an assistant answer, first occurrence order. */
    fun extractDocRefs(text: String): List<String> =
        docRefRegex.findAll(text).map { it.groupValues[1].lowercase() }.distinct().toList()

    /**
     * Newest-first whole turns kept under [budgetChars] cumulative characters, then restored to
     * oldest-first order. Oldest turns are dropped first; [TrimmedHistory.omittedEarlier] is true
     * when anything was dropped so the prompt can note it. The current question is never part of
     * [history] and so is never dropped here.
     */
    fun trimHistory(
        history: List<AiChatTurn>,
        budgetChars: Int = CHAT_HISTORY_CHAR_BUDGET,
    ): TrimmedHistory {
        val kept = ArrayDeque<AiChatTurn>()
        var used = 0
        for (turn in history.asReversed()) {
            val cost = turn.text.length
            if (used + cost > budgetChars && kept.isNotEmpty()) break
            kept.addFirst(turn)
            used += cost
        }
        return TrimmedHistory(kept.toList(), omittedEarlier = kept.size < history.size)
    }

    /**
     * Parse raw tag strings out of a model response: the first JSON string array found wins
     * (models often wrap it in prose or code fences). Suggestions already on the document and
     * case-insensitive duplicates are dropped. No cap is applied here — see [rankTagSuggestions].
     */
    fun parseTagSuggestions(response: String, currentTags: List<String>): List<String> {
        val current = currentTags.map { it.lowercase() }.toHashSet()
        return firstStringArray(response)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in current }
            .distinctBy { it.lowercase() }
    }

    /**
     * Full tag-suggestion pipeline from a model [response]:
     *  1. parse + drop blanks / current-tags / case-insensitive dups ([parseTagSuggestions]);
     *  2. **canonicalize** each suggestion to the existing [vocabulary] tag's exact spelling when
     *     it matches case-insensitively;
     *  3. classify [TagSuggestion.isNew] = not in the vocabulary (after canonicalization);
     *  4. **rank** existing-vocabulary suggestions before new ones (stable within each group);
     *  5. **cap** new tags at [maxNew] (never above [perDocMax]) and the whole list at [perDocMax].
     */
    fun rankTagSuggestions(
        response: String,
        currentTags: List<String>,
        vocabulary: List<String>,
        perDocMax: Int = MAX_TAG_SUGGESTIONS,
        maxNew: Int = MAX_NEW_TAGS_PER_DOC,
    ): List<TagSuggestion> {
        val canonical = vocabulary.associateBy { it.lowercase() }
        val newCap = maxNew.coerceAtMost(perDocMax)
        val classified = parseTagSuggestions(response, currentTags).map { raw ->
            val existing = canonical[raw.lowercase()]
            if (existing != null) TagSuggestion(existing, isNew = false) else TagSuggestion(raw, isNew = true)
        }
        val existing = classified.filter { !it.isNew }
        val fresh = classified.filter { it.isNew }.take(newCap)
        return (existing + fresh).take(perDocMax)
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
