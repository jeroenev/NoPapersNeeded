package dev.npn.core.ai

import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The ambient facts every prompt needs from outside the model call: which language the app runs in,
 * and what today's date is.
 *
 * Both exist because a prompt that omits them gets them guessed at. With no language, a model
 * writing a title or a brand-new tag falls back to the language of the document (or of these
 * English instructions), so a Dutch library slowly fills with English tags. With no date, the model
 * has no anchor for the date it reads off a page, and a misread year looks exactly like a real one.
 *
 * [today] is prompt text (a plain ISO string) and deliberately day-granular, so every document in
 * one Edit Run shares a byte-identical system message — see [AiPrompts.editSystem].
 */
internal data class AiPromptContext(
    /** The app's language as the model is told it, e.g. `Dutch (nl)`; null when it is unknown. */
    val appLanguage: String?,
    /** Today in the device's own timezone, ISO `yyyy-MM-dd`. */
    val today: String,
) {
    companion object {
        /**
         * Build a context from an app-language tag (`nl`, `nl-NL`, `nl_NL`, or null/blank when the
         * effective language is unknown) and a [clock].
         *
         * An unrecognised tag is passed through as its bare subtag rather than dropped: "the app
         * runs in pt" still steers a model correctly, and naming every locale on earth is not this
         * layer's job.
         */
        fun of(
            languageTag: String?,
            clock: EpochClock = SystemEpochClock,
        ): AiPromptContext =
            AiPromptContext(
                appLanguage = languageLabel(languageTag),
                today =
                    Instant
                        .fromEpochMilliseconds(clock.nowMillis())
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .date
                        .toString(),
            )

        private fun languageLabel(languageTag: String?): String? {
            val subtag =
                languageTag
                    ?.trim()
                    ?.substringBefore('-')
                    ?.substringBefore('_')
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null
            return LANGUAGE_NAMES[subtag]?.let { "$it ($subtag)" } ?: subtag
        }

        /**
         * English names for the languages this app plausibly runs in, so a prompt can say
         * "Dutch (nl)" instead of "nl". Deliberately not exhaustive; [languageLabel] falls back to
         * the raw subtag.
         */
        private val LANGUAGE_NAMES =
            mapOf(
                "en" to "English",
                "nl" to "Dutch",
                "fr" to "French",
                "de" to "German",
                "es" to "Spanish",
                "it" to "Italian",
                "pt" to "Portuguese",
            )
    }
}

/**
 * Every instruction this app sends a model, in one place: the assistant's system prompt, the
 * page-transcription prompt, and the AI-edit flow's system + user messages.
 *
 * They live together and away from [AiFacadeImpl] for two reasons. Prompt wording is the app's
 * behaviour for these features — it is not a detail of dispatching an HTTP call — and it is pure
 * text, so gathering it here is what makes it assertable in tests without a model.
 *
 * Sections are assembled from individually `trimIndent()`-ed literals and joined, rather than
 * written as one big raw string with list interpolations inside. `trimIndent()` runs *after*
 * interpolation, so a single multi-line interpolated value (a rule list) drops the common indent to
 * zero and leaves the entire prompt indented. Per-section trimming keeps what we send clean.
 */
internal object AiPrompts {
    /** Cap on how much document text one edit-suggestion request carries. */
    const val MAX_DOC_TEXT_CHARS = 12_000

    // ---------- assistant chat ----------

    /**
     * The library assistant's system prompt: what the archive is, which tool answers which kind of
     * question, how to cite a document, and which language to write in.
     */
    fun chatSystem(
        context: AiPromptContext,
        fields: List<FieldDef>,
    ): String =
        listOf(
            chatRole(fields),
            CHAT_TOOLS,
            CHAT_STYLE,
            "Today is ${context.today}; resolve relative dates like \"last month\" or \"this year\" against it.",
            chatLanguageRule(context),
        ).joinToString("\n\n")

    /**
     * Which language the assistant writes in. The app's language is only the default: the language
     * the user actually writes in wins, because an answer the user cannot read is useless however
     * the app is configured.
     */
    private fun chatLanguageRule(context: AiPromptContext): String =
        context.appLanguage?.let { language ->
            "This app runs in $language, so write in that language by default. When the user " +
                "writes to you in another language, answer in theirs instead — the language the " +
                "user writes in always wins. Titles, tags and quoted document text stay exactly " +
                "as stored; never translate them."
        } ?: "Answer in the language the user writes in."

    private fun chatRole(fields: List<FieldDef>): String =
        """
        You are the assistant inside "no-papers-needed", a personal paperless document
        manager. The user's scanned documents (PDFs) are indexed locally: OCR text, tags,
        filenames, and metadata fields. The fields in this library are:
        ${LibraryAiTools.fieldVocabulary(fields)}. Documents may also carry field keys that
        have no definition yet. ocrEngine is maintained by the app rather than the user: it
        names the engine whose transcription is indexed for search, so it answers questions
        about how a document was read, not what it says.
        """.trimIndent()

    private val CHAT_TOOLS =
        """
        Use the provided tools to find documents before answering; never invent documents
        or their contents. Prefer search_documents for content questions, list_tags /
        documents_with_tag for organisation questions, search_filenames when the user
        remembers a file name, search_fields to look documents up by a field value — including
        "has a field" queries like "documents with a test2 field" (call list_fields when unsure
        of a key; leave search_fields' value empty to match any document that has the field) and
        date or number ranges via from/to — and get_document_text to read a specific document.
        Field values are also part of the full-text search_documents index; field keys are not.
        """.trimIndent()

    private val CHAT_STYLE =
        """
        When you mention a specific document, cite it inline exactly as [doc:<docId>] using
        the full docId from the tool results, so the app can link it. Be concise. If nothing
        matches, say so plainly.
        """.trimIndent()

    // ---------- page transcription ----------

    /**
     * The page-transcription prompt. Written to make the reply *usable as archived text*, which is
     * a stricter goal than "read the page": every instruction here exists to stop the model doing
     * something a local OCR engine cannot do — summarising, translating, correcting, describing, or
     * introducing itself. The output goes verbatim into a write-once sidecar and into the search
     * index, so invented or reflowed text is not a cosmetic problem.
     *
     * Note what this prompt does NOT do with [AiPromptContext.appLanguage]: nothing. A
     * transcription is the page's own words, so the app's language has no business in it. The
     * language that matters here is the page's, and it arrives as the per-call hint in
     * [pageRequest].
     */
    fun ocrSystem(context: AiPromptContext): String = "$OCR_RULES\n\n${ocrDateAnchor(context)}"

    /** The user turn accompanying the page image: which language to expect, and what to do. */
    fun pageRequest(lang: String): String = "Language hint: $lang. Transcribe this page."

    /**
     * Today's date as a *reading* aid only.
     *
     * The transcription itself must never be reasoned into shape, but a vision model choosing
     * between two readings of a damaged digit is already guessing, and a plausible-year check is a
     * better guess than a coin flip. Interpreting dates is explicitly a later step's job (see
     * [dateGuidance]) precisely because this one must not reformat them.
     */
    private fun ocrDateAnchor(context: AiPromptContext): String =
        """
        For reference, today is ${context.today}. Use it only to sanity-check a digit you already
        cannot read cleanly — a year that reads as 2O24 is 2024.
        It never overrides what the page shows, and never justifies supplying a date the page
        does not print.
        """.trimIndent()

    private val OCR_RULES =
        """
        You are an OCR engine. You transcribe one scanned page image into plain text.

        Rules:
        - Output ONLY the page's text. No preamble, no commentary, no explanation of what the
          document is, no markdown code fences.
        - Transcribe VERBATIM in the page's own language. Never translate, summarise, rephrase,
          correct spelling, or fix what looks like an error: reproduce what is printed.
        - Keep the natural reading order and the line and paragraph breaks of the page.
        - Render tables as plain text rows, keeping columns separated by spaces or tabs.
        - Transcribe handwriting too. For a word you genuinely cannot read, write [?] in its
          place rather than guessing.
        - Copy dates character for character, keeping the page's own field order, separators and
          year length: 03/04/2024 stays 03/04/2024, and 5-6-24 stays 5-6-24. Never reorder a date
          into another convention and never expand a two-digit year. A later step converts dates
          to a single format, and it can only do that correctly from what the page actually says.
        - Do not describe images, logos or stamps; transcribe only text that appears in them.
        - If the page contains no text at all (blank, or purely an image), reply with exactly
          ${AiText.BLANK_PAGE_MARKER} and nothing else.
        """.trimIndent()

    // ---------- AI edit ----------

    /**
     * The single-request system prompt for the AI-edit flow. Sections are included only for what
     * the run actually asked for, so a tags-only run gets a tags-only contract and a custom-prompt
     * run still gets the same structured reply shape.
     *
     * Library-wide context (tag vocabulary, each text field's existing values) lives HERE, not in
     * the per-doc user message: it is identical for every document of a batch, so keeping the
     * whole system message batch-constant lets prefix/KV caches on the endpoint skip re-evaluating
     * the (potentially large) vocabulary for every document. Only per-doc data goes in the user
     * message — which is also why [AiPromptContext.today] is a date and not a timestamp.
     */
    fun editSystem(
        request: AiEditRequest,
        fields: List<AiFieldSpec>,
        max: Int,
        maxNew: Int,
        vocabulary: List<String>,
        context: AiPromptContext,
    ): String =
        buildList {
            add(EDIT_INTRO)
            add(editRules(request, fields, max, maxNew, context).joinToString("\n"))
            add(editContract(request, fields))
            if (fields.any { it.def.type == FieldType.DATE }) add(dateGuidance(context))
            editLibraryContext(request, fields, vocabulary)?.let { add(it) }
        }.joinToString("\n\n")

    /**
     * Per-doc data for one edit-suggestion request: the current value of every requested field,
     * the OCR text, extra wishes.
     */
    fun editUser(
        fileName: String,
        currentFields: Map<String, String>,
        fields: List<AiFieldSpec>,
        request: AiEditRequest,
        currentTags: List<String>,
        text: String?,
    ): String =
        buildString {
            appendLine("File name: $fileName")
            for (spec in fields) {
                val current = currentFields[spec.key]?.takeIf { it.isNotBlank() } ?: continue
                appendLine("Current ${spec.def.label}: $current")
            }
            if (request.tags || request.tagRemovals) {
                appendLine(
                    "Tags already on this document: ${currentTags.ifEmpty { listOf("(none)") }.joinToString(", ")}",
                )
            }
            appendLine()
            if (text != null) {
                appendLine("Document text:")
                appendLine(text.take(MAX_DOC_TEXT_CHARS))
            } else {
                appendLine("Document text: (no OCR text available)")
            }
            request.customPrompt?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("User's extra instructions:")
                appendLine(it.trim())
            }
        }

    private val EDIT_INTRO =
        """
        You propose edits for one document in a personal paperless archive. Read the
        provided document text and current metadata carefully; never invent facts the
        document text does not support.
        """.trimIndent()

    private fun editRules(
        request: AiEditRequest,
        fields: List<AiFieldSpec>,
        max: Int,
        maxNew: Int,
        context: AiPromptContext,
    ): List<String> =
        buildList {
            if (request.tags) add(addTagsRule(max, maxNew, context))
            if (request.tagRemovals) add(REMOVE_TAGS_RULE)
            for (spec in fields) {
                val description = fieldDescription(spec, context)
                add("\"fields.${spec.key}\" (${spec.def.label}): $description. ${modeRule(spec.mode)}")
            }
        }

    private fun addTagsRule(
        max: Int,
        maxNew: Int,
        context: AiPromptContext,
    ): String {
        val newTagRule =
            if (maxNew <= 0) {
                "Do NOT invent brand-new tags: use ONLY tags from the provided vocabulary."
            } else {
                val language =
                    context.appLanguage?.let { "Write brand-new tags in $it, the app's language. " }.orEmpty()
                "${language}Strongly prefer existing vocabulary tags and propose at most $maxNew brand-new tags."
            }
        return "\"add_tags\": tags to ADD to this document (must not already be on it). " +
            "When an existing library tag fits, copy it VERBATIM (exact spelling and case); " +
            "only invent a new tag when none fits. Tags are short lowercase labels (topic, " +
            "document kind, organisation, year). $newTagRule At most $max, best first."
    }

    private const val REMOVE_TAGS_RULE =
        "\"remove_tags\": tags currently on this document that should be REMOVED. " +
            "Only list tags from the document's current tag list, verbatim. Leave empty " +
            "unless the document genuinely should no longer have those tags."

    private fun modeRule(mode: AiFieldMode): String =
        when (mode) {
            AiFieldMode.ALL -> {
                "Suggest a value even when one already exists (you may propose a better replacement)."
            }

            AiFieldMode.UNFILLED -> {
                "Only suggest a value when the field is currently empty; otherwise use null."
            }
        }

    /**
     * Field answers sit under one `"fields"` object rather than beside `add_tags`/`remove_tags`, so
     * a user field that happens to be called `add_tags` cannot collide with the tag contract.
     */
    private fun editContract(
        request: AiEditRequest,
        fields: List<AiFieldSpec>,
    ): String {
        val contract =
            buildList {
                if (request.tags) add("\"add_tags\": [\"tag\"]")
                if (request.tagRemovals) add("\"remove_tags\": [\"tag\"]")
                if (fields.isNotEmpty()) {
                    add("\"fields\": {" + fields.joinToString(", ") { "\"${it.key}\": ${it.def.type.aiLiteral}" } + "}")
                }
            }
        return """
            Reply with ONLY a JSON object containing exactly these keys, no prose, no code
            fences: {${contract.joinToString(", ")}}
            Use null / empty arrays for anything the document does not clearly support. Follow
            the user's extra instructions when they are provided, mapping their intent onto the
            allowed keys.
            """.trimIndent()
    }

    private fun editLibraryContext(
        request: AiEditRequest,
        fields: List<AiFieldSpec>,
        vocabulary: List<String>,
    ): String? =
        buildList {
            if (request.tags || request.tagRemovals) {
                add("Existing tags in this library: " + vocabulary.ifEmpty { listOf("(none)") }.joinToString(", "))
            }
            for (spec in fields) {
                if (spec.vocabulary.isEmpty()) continue
                add(
                    "Existing values of ${spec.def.label} (\"${spec.key}\") in this library — copy one VERBATIM " +
                        "when it fits: " + spec.vocabulary.joinToString(", "),
                )
            }
        }.ifEmpty { null }?.joinToString("\n")

    /**
     * How to turn a date printed on a page into one ISO date, sent only when the run asks for
     * a DATE field.
     *
     * Two failure modes, one anchor. A model with no idea what day it is cannot tell a misread year
     * from a real one, so it will happily date a doctor's note to 2087. But clamping to the past
     * would be just as wrong — archives hold decades-old paperwork and tickets for next year's
     * concert — so today is given as a plausibility check the model may overrule from the text, not
     * as a bound. Nothing here is enforced after the fact: [AiText.parseMetadataSuggestion] does
     * not reject a date for being far away, because it cannot tell which far-away dates are real.
     *
     * The second mode is dd/mm vs mm/dd, which is why this text insists the *document* decides.
     * The app's language is the wrong signal — a US invoice in a Dutch-language app is still
     * month-first — so the model is pointed at the document's own language, currency and address.
     */
    private fun dateGuidance(context: AiPromptContext): String =
        """
        Today is ${context.today}. Document dates are usually in the past, sometimes by decades,
        and genuinely future dates exist too (a ticket for next year's concert, a policy starting
        next year), so never move a date just to put it in the past. Use today only as a
        plausibility check: a date wildly far from today for this kind of document is more likely a
        misreading, so re-read it and prefer what the text supports. If the document really is
        dated that way, keep it.

        Numeric dates are ambiguous, and the DOCUMENT decides how to read them, not this app's
        language or yours: 03/04/2024 is 3 April in European documents (dd/mm/yyyy) but 4 March in
        US ones (mm/dd/yyyy). Judge from the document's own language, currency, address and the way
        it words other dates. A value above 12 can only be the day. Prefer a written-out month
        elsewhere on the page over a numeric one, and when the order stays genuinely unclear,
        choose the reading closer to today.
        """.trimIndent()

    /**
     * What to say about one field. The well-known keys keep their tuned wording; every other field
     * gets its type's generic instruction plus the user's hint, which is what makes the hint the
     * quality lever for custom fields.
     */
    private fun fieldDescription(
        spec: AiFieldSpec,
        context: AiPromptContext,
    ): String {
        val curated =
            when (spec.key) {
                FieldKeys.TITLE -> {
                    // A title is the app's own label for the document, so it belongs in the language
                    // the user reads the app in. Only when that is unknown does the document's own
                    // language become the best available default.
                    val language = context.appLanguage?.let { "in $it" } ?: "in the document's own language"
                    "a short, human-readable title $language — what the document IS, never a file " +
                        "name. The English example \"Electricity invoice January 2024\" shows the " +
                        "shape, not the language to write in. Keep company names, reference numbers " +
                        "and other proper nouns exactly as printed"
                }

                FieldKeys.CORRESPONDENT -> {
                    "the other party — the company, institution or person that sent or issued the " +
                        "document, never the archive owner. When an existing value from the " +
                        "provided list matches, copy it VERBATIM (exact spelling and case); only " +
                        "introduce a new name when none fits"
                }

                FieldKeys.DOC_DATE -> {
                    "the document's own date (invoice date, letter date, issue date) as YYYY-MM-DD, " +
                        "never the scan or import date"
                }

                else -> {
                    null
                }
            }
        val hint =
            spec.def.hint
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        return listOfNotNull(curated ?: typeInstruction(spec), hint).joinToString(". ")
    }

    private fun typeInstruction(spec: AiFieldSpec): String {
        val reuse =
            if (spec.vocabulary.isNotEmpty()) {
                "; when an existing value from the provided list matches, copy it VERBATIM"
            } else {
                ""
            }
        return when {
            spec.def.type == FieldType.DATE -> {
                "the ${spec.def.label} as a date in YYYY-MM-DD, taken from the document text"
            }

            spec.def.type.isNumeric -> {
                "the ${spec.def.label} as a plain number (no currency symbol, unit or grouping)"
            }

            else -> {
                "the ${spec.def.label}, taken from the document text$reuse"
            }
        }
    }
}
