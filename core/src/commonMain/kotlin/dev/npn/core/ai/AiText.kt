package dev.npn.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Result of budget-trimming a conversation: the [turns] to send (oldest-first) and whether any
 * older turns were [omittedEarlier] so the prompt can add a "earlier conversation omitted" note.
 */
data class TrimmedHistory(
    val turns: List<AiChatTurn>,
    val omittedEarlier: Boolean,
)

/** Pure text helpers for the AI layer, split out so they are unit-testable without a model. */
object AiText {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private val docRefRegex = Regex("\\[doc:([0-9a-fA-F]{64})]")
    private val isoDateRegex = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

    /** Cap on accepted title suggestions; anything longer is model rambling, not a title. */
    const val MAX_TITLE_SUGGESTION_CHARS = 200

    const val MAX_TAG_SUGGESTIONS = 8

    /**
     * Default cap on genuinely-new (non-vocabulary) tags proposed per document, ranked last.
     * User-configurable via settings ([dev.npn.core.app.AiTagging.NEW_TAGS_RANGE]); this constant
     * is only the fallback when no explicit cap is supplied.
     */
    const val MAX_NEW_TAGS_PER_DOC = 5

    /**
     * Character budget for conversation history sent as real user/assistant messages. Derived
     * from the default `contextLength = 128_000` tokens at ~3 chars/token, reserving room for the
     * system prompt, tool schemas, and the answer. Large enough that normal chats never trigger
     * trimming; it exists only to bound pathological growth without an arbitrary turn-count cliff.
     */
    const val CHAT_HISTORY_CHAR_BUDGET = 200_000

    /** Full docIds cited as `[doc:<hash>]` in an assistant answer, first occurrence order. */
    fun extractDocRefs(text: String): List<String> =
        docRefRegex
            .findAll(text)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()

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
    fun parseTagSuggestions(
        response: String,
        currentTags: List<String>,
    ): List<String> {
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
        val classified =
            parseTagSuggestions(response, currentTags).map { raw ->
                val existing = canonical[raw.lowercase()]
                if (existing != null) TagSuggestion(existing, isNew = false) else TagSuggestion(raw, isNew = true)
            }
        val existing = classified.filter { !it.isNew }
        val fresh = classified.filter { it.isNew }.take(newCap)
        return (existing + fresh).take(perDocMax)
    }

    /**
     * Parse a metadata-suggestion object out of a model [response]: the first JSON object found
     * wins (models often wrap it in prose or code fences). Each field is validated independently:
     *  - **title**: trimmed; dropped when blank, absurdly long (> [MAX_TITLE_SUGGESTION_CHARS])
     *    or equal to [currentTitle];
     *  - **correspondent**: trimmed, **canonicalized** to the existing [correspondents] entry's
     *    exact spelling when it matches case-insensitively; dropped when blank or equal to
     *    [currentCorrespondent] (case-insensitively);
     *  - **date**: dropped unless a plausible ISO `yyyy-MM-dd` date differing from
     *    [currentDocDate].
     */
    fun parseMetadataSuggestion(
        response: String,
        currentTitle: String?,
        currentCorrespondent: String?,
        currentDocDate: String?,
        correspondents: List<String>,
    ): MetadataSuggestion {
        val obj = firstJsonObject(response) ?: return MetadataSuggestion.EMPTY
        val title =
            obj
                .stringAt("title")
                ?.takeIf { it.length <= MAX_TITLE_SUGGESTION_CHARS && it != currentTitle?.trim() }
        val correspondent =
            obj
                .stringAt("correspondent")
                ?.let { raw -> correspondents.firstOrNull { it.equals(raw, ignoreCase = true) } ?: raw }
                ?.takeIf { !it.equals(currentCorrespondent?.trim(), ignoreCase = true) }
        val docDate =
            obj
                .stringAt("date")
                ?.takeIf { isPlausibleIsoDate(it) && it != currentDocDate?.trim() }
        return MetadataSuggestion(title = title, correspondent = correspondent, docDate = docDate)
    }

    /** Structurally valid `yyyy-MM-dd` with a sane month/day range (no calendar-exact check). */
    fun isPlausibleIsoDate(value: String): Boolean {
        val match = isoDateRegex.matchEntire(value) ?: return false
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        return month in 1..12 && day in 1..31
    }

    private fun JsonObject.stringAt(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun firstJsonObject(text: String): JsonObject? {
        var start = text.indexOf('{')
        while (start >= 0) {
            // Try every candidate close brace: the first parseable substring wins. Suggestion
            // objects are small and flat, so this stays cheap even on prose-wrapped output.
            var end = text.indexOf('}', startIndex = start)
            while (end >= 0) {
                val parsed =
                    runCatching {
                        json.decodeFromString<JsonObject>(text.substring(start, end + 1))
                    }.getOrNull()
                if (parsed != null) return parsed
                end = text.indexOf('}', startIndex = end + 1)
            }
            start = text.indexOf('{', startIndex = start + 1)
        }
        return null
    }

    private fun firstStringArray(text: String): List<String> {
        var start = text.indexOf('[')
        while (start >= 0) {
            val end = text.indexOf(']', startIndex = start)
            if (end < 0) return emptyList()
            val candidate = text.substring(start, end + 1)
            val parsed =
                runCatching {
                    json.decodeFromString<JsonArray>(candidate).map { it.jsonPrimitive.content }
                }.getOrNull()
            if (parsed != null) return parsed
            start = text.indexOf('[', startIndex = start + 1)
        }
        return emptyList()
    }
}
