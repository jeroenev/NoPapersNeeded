package dev.npn.core.ai

import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldValues
import dev.npn.core.util.NpnJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Pure text helpers for the AI layer, split out so they are unit-testable without a model. */
object AiText {
    private val json = NpnJson.Lenient
    private val docRefRegex = Regex("\\[doc:([0-9a-fA-F]{64})]")

    /** Cap on accepted title suggestions; anything longer is model rambling, not a title. */
    const val MAX_TITLE_SUGGESTION_CHARS = 200

    /**
     * What a page-OCR prompt tells the model to answer for a page that carries no text at all.
     *
     * A sentinel exists because "" is ambiguous on the wire: a blank page and a call that produced
     * no answer both come back as no text, and only the second one is worth retrying. See
     * [parseTranscription].
     */
    const val BLANK_PAGE_MARKER = "[[BLANK]]"

    /** Fences a model may wrap a transcription in even when told not to. */
    private val codeFenceRegex = Regex("^\\s*```[a-zA-Z]*\\s*\\n(.*?)\\n?\\s*```\\s*$", RegexOption.DOT_MATCHES_ALL)

    /**
     * Read one page's transcription out of a vision-model reply.
     *
     * Three outcomes, not two:
     *  - **null** — no usable answer (an empty message, or a refusal that carried no text). The
     *    caller may retry; the page's text is still unknown.
     *  - **""** — the model explicitly reported a page with no text ([BLANK_PAGE_MARKER]). A
     *    definitive answer: retrying would only buy the same one again.
     *  - anything else — the transcription, unwrapped from a markdown code fence if the model
     *    added one.
     *
     * Nothing else is normalised. The text is the document's own content and goes into a write-once
     * sidecar, so trimming or reflowing it here would silently alter what the archive stores.
     */
    fun parseTranscription(response: String): String? {
        val unfenced = codeFenceRegex.matchEntire(response)?.groupValues?.get(1) ?: response
        val trimmed = unfenced.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.equals(BLANK_PAGE_MARKER, ignoreCase = true)) return ""
        return trimmed
    }

    /** Full docIds cited as `[doc:<hash>]` in an assistant answer, first occurrence order. */
    fun extractDocRefs(text: String): List<String> =
        docRefRegex
            .findAll(text)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()

    /**
     * Parse raw tag strings out of a model response: the first JSON string array found wins
     * (models often wrap it in prose or code fences). Suggestions already on the document and
     * case-insensitive duplicates are dropped. No cap is applied here — see [parseEditSuggestion].
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
     * Parse a combined edit suggestion object out of a model [response] (first JSON object wins):
     *  - **add_tags** (alias "tags"): classified/capped via [classifyTags];
     *  - **remove_tags**: filtered to tags actually on the document, canonicalized to their current
     *    spelling;
     *  - **fields**: one entry per requested [AiFieldSpec], read from the nested `"fields"` object
     *    (or the top level, for models that flatten it) and validated by [parseFieldSuggestion].
     */
    fun parseEditSuggestion(
        response: String,
        currentFields: Map<String, String>,
        fields: List<AiFieldSpec>,
        currentTags: List<String>,
        vocabulary: List<String>,
        maxTags: Int,
        maxNewTags: Int,
    ): AiEditSuggestion {
        val obj = firstJsonObject(response) ?: return AiEditSuggestion.EMPTY
        val addTags =
            classifyTags(
                obj.stringArrayAt("add_tags").ifEmpty { obj.stringArrayAt("tags") },
                currentTags,
                vocabulary,
                perDocMax = maxTags,
                maxNew = maxNewTags,
            )
        val removeTags =
            obj
                .stringArrayAt("remove_tags")
                .mapNotNull { raw -> currentTags.firstOrNull { it.equals(raw, ignoreCase = true) } }
                .distinct()
        return AiEditSuggestion(
            addTags = addTags,
            removeTags = removeTags,
            metadata = parseFieldSuggestion(obj, currentFields, fields),
        )
    }

    /**
     * The field half of a reply. Each requested field is judged independently: the raw answer is
     * canonicalized for the field's type ([FieldValues.canonicalize]) and dropped when it does not
     * fit; a TEXT answer matching an existing vocabulary value case-insensitively takes that exact
     * spelling; a value equal to the document's current one (case-insensitively for text) is
     * dropped; an UNFILLED-mode field drops any value when the document already has one (the model
     * is told to return null, this is the defensive backstop); a title longer than
     * [MAX_TITLE_SUGGESTION_CHARS] is dropped. Nothing the run did not ask for is ever accepted.
     */
    fun parseFieldSuggestion(
        obj: JsonObject,
        currentFields: Map<String, String>,
        fields: List<AiFieldSpec>,
    ): FieldSuggestion {
        val nested = obj["fields"] as? JsonObject
        val values =
            fields.mapNotNull { spec ->
                val raw = nested?.valueAt(spec.key) ?: obj.valueAt(spec.key)
                accepted(spec, raw, currentFields[spec.key]?.trim())?.let { spec.key to it }
            }
        return FieldSuggestion(values.toMap())
    }

    /** One field's accepted canonical value, or null when the answer is absent, off-type, redundant or not allowed. */
    private fun accepted(
        spec: AiFieldSpec,
        raw: String?,
        current: String?,
    ): String? {
        if (raw == null) return null
        if (spec.mode == AiFieldMode.UNFILLED && !current.isNullOrBlank()) return null
        val canonical = canonicalAnswer(spec, raw) ?: return null
        return canonical.takeUnless { current != null && sameValue(spec, it, current) }
    }

    private fun canonicalAnswer(
        spec: AiFieldSpec,
        raw: String,
    ): String? {
        val canonical = (FieldValues.canonicalize(spec.def.type, raw) as? FieldParse.Ok)?.canonical ?: return null
        if (spec.key == FieldKeys.TITLE && canonical.length > MAX_TITLE_SUGGESTION_CHARS) return null
        if (!spec.def.type.isTextual) return canonical
        return spec.vocabulary.firstOrNull { it.equals(canonical, ignoreCase = true) } ?: canonical
    }

    private fun sameValue(
        spec: AiFieldSpec,
        proposed: String,
        current: String,
    ): Boolean = if (spec.def.type.isTextual) proposed.equals(current, ignoreCase = true) else proposed == current

    /** A string, number or boolean answer as text; null, absent and blank read as "no answer". */
    private fun JsonObject.valueAt(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The shared tag-classification tail of [parseEditSuggestion]:
     * drop blanks + current tags + case-insensitive dups, canonicalize to [vocabulary] spelling,
     * rank existing-vocabulary before new, cap new at [maxNew] and the total at [perDocMax].
     */
    private fun classifyTags(
        raw: List<String>,
        currentTags: List<String>,
        vocabulary: List<String>,
        perDocMax: Int,
        maxNew: Int,
    ): List<TagSuggestion> {
        val canonical = vocabulary.associateBy { it.lowercase() }
        val current = currentTags.map { it.lowercase() }.toHashSet()
        val newCap = maxNew.coerceAtMost(perDocMax)
        val classified =
            raw
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.lowercase() !in current }
                .distinctBy { it.lowercase() }
                .map { rawTag ->
                    val existing = canonical[rawTag.lowercase()]
                    if (existing !=
                        null
                    ) {
                        TagSuggestion(existing, isNew = false)
                    } else {
                        TagSuggestion(rawTag, isNew = true)
                    }
                }
        val existing = classified.filter { !it.isNew }
        val fresh = classified.filter { it.isNew }.take(newCap)
        return (existing + fresh).take(perDocMax)
    }

    private fun JsonObject.stringArrayAt(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

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
