package dev.npn.core.ai

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

// A prompt's date must be assertable, so every context in this file is built on a fixed clock.
private val FIXED_CLOCK =
    object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-07T10:15:00Z")
    }

private fun context(languageTag: String?) = AiPromptContext.of(languageTag, FIXED_CLOCK)

/**
 * [AiPromptContext]: how an app-language tag and a clock become prompt-ready facts.
 *
 * The classes in this file assert the *facts* a prompt carries (today's date, the app's language,
 * who decides how to read a date) rather than its wording, so rephrasing a prompt stays free while
 * dropping one of its guarantees does not.
 */
class AiPromptContextTest {
    @Test
    fun namesAKnownLanguageAndKeepsItsTag() {
        context("nl").appLanguage shouldContain "Dutch (nl)"
    }

    @Test
    fun acceptsARegionalTagAndReducesItToTheLanguage() {
        // Locale.getDefault().toString() can hand us nl_NL, and settings could hold nl-NL.
        context("nl-NL").appLanguage shouldContain "Dutch (nl)"
        context("nl_NL").appLanguage shouldContain "Dutch (nl)"
        context("NL").appLanguage shouldContain "Dutch (nl)"
    }

    @Test
    fun passesAnUnnamedLanguageThroughAsItsBareSubtag() {
        // Better a prompt saying "pt" than no language at all; we do not name every locale.
        context("pt-BR").appLanguage shouldContain "pt"
    }

    @Test
    fun reportsNoLanguageWhenTheTagIsMissingOrBlank() {
        context(null).appLanguage shouldBe null
        context("  ").appLanguage shouldBe null
    }

    @Test
    fun todayIsAnIsoDate() {
        // Rendered in the device's own timezone, so only the shape is fixed across machines.
        context("nl").today shouldMatch Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}

/** The assistant's system prompt: tool contract, citations, date anchor and language rule. */
class ChatPromptTest {
    private val dutch = AiPromptContext.of("nl", FIXED_CLOCK)
    private val fields = FieldKeys.seedDefinitions()

    @Test
    fun chatDefaultsToTheAppLanguageButStillFollowsTheUser() {
        val prompt = AiPrompts.chatSystem(dutch, fields)
        prompt shouldContain "Dutch (nl)"
        prompt shouldContain "default"
        // The whole point: the app's language is a default, never a cage.
        prompt shouldContain "the language the user writes in always wins"
    }

    @Test
    fun chatFallsBackToTheUsersLanguageWhenTheAppLanguageIsUnknown() {
        val prompt = AiPrompts.chatSystem(context(null), fields)
        prompt shouldContain "Answer in the language the user writes in"
        prompt shouldNotContain "runs in"
    }

    @Test
    fun chatCarriesTodaySoRelativeDatesResolve() {
        AiPrompts.chatSystem(dutch, fields) shouldContain dutch.today
    }

    @Test
    fun chatStillCarriesItsToolAndCitationContract() {
        val prompt = AiPrompts.chatSystem(dutch, fields)
        prompt shouldContain "[doc:<docId>]"
        prompt shouldContain "search_documents"
        // The field vocabulary is now built at runtime from the live registry.
        prompt shouldContain LibraryAiTools.fieldVocabulary(fields)
    }
}

/** The page-transcription prompt: verbatim discipline, date preservation, blank-page contract. */
class PagePromptTest {
    private val dutch = AiPromptContext.of("nl", FIXED_CLOCK)

    @Test
    fun ocrCarriesTodayAsAReadingAidOnly() {
        val prompt = AiPrompts.ocrSystem(dutch)
        prompt shouldContain dutch.today
        prompt shouldContain "never overrides what the page shows"
    }

    @Test
    fun ocrForbidsReformattingADateSoALaterStepCanStillReadIt() {
        val prompt = AiPrompts.ocrSystem(dutch)
        prompt shouldContain "03/04/2024 stays 03/04/2024"
        prompt shouldContain "never expand a two-digit year"
    }

    @Test
    fun ocrNeverMentionsTheAppLanguage() {
        // A transcription is the page's own words; the app's language could only bias it.
        AiPrompts.ocrSystem(dutch) shouldNotContain "Dutch"
        AiPrompts.ocrSystem(dutch) shouldContain "the page's own language"
    }

    @Test
    fun ocrKeepsTheBlankPageContract() {
        AiPrompts.ocrSystem(dutch) shouldContain AiText.BLANK_PAGE_MARKER
    }

    @Test
    fun pageRequestPassesTheLanguageHint() {
        AiPrompts.pageRequest("nld") shouldContain "Language hint: nld"
    }
}

/** The AI-edit flow's system prompt: rules, reply contract, language and date guidance. */
class EditPromptTest {
    private val dutch = AiPromptContext.of("nl", FIXED_CLOCK)
    private val seeds = FieldKeys.seedDefinitions().associateBy { it.key }

    /** A requested field as a spec: the registry def, the run's mode, and any vocabulary. */
    private fun spec(
        key: String,
        mode: AiFieldMode = AiFieldMode.UNFILLED,
        vocabulary: List<String> = emptyList(),
    ) = AiFieldSpec(seeds.getValue(key), mode, vocabulary)

    private fun editPrompt(
        request: AiEditRequest = AiEditRequest(),
        fields: List<AiFieldSpec> = emptyList(),
        context: AiPromptContext = dutch,
        vocabulary: List<String> = listOf("invoice"),
    ) = AiPrompts.editSystem(request, fields, max = 8, maxNew = 5, vocabulary, context)

    private val dateFields = listOf(spec(FieldKeys.DOC_DATE))

    @Test
    fun editAsksForNewTagsInTheAppLanguage() {
        editPrompt(AiEditRequest(tags = true)) shouldContain "brand-new tags in Dutch (nl)"
    }

    @Test
    fun editAsksForATitleInTheAppLanguage() {
        val prompt = editPrompt(fields = listOf(spec(FieldKeys.TITLE)))
        prompt shouldContain "title in Dutch (nl)"
        prompt shouldNotContain "in the document's own language"
    }

    @Test
    fun editFallsBackToTheDocumentsLanguageForATitleWhenTheAppLanguageIsUnknown() {
        val prompt = editPrompt(fields = listOf(spec(FieldKeys.TITLE)), context = context(null))
        prompt shouldContain "in the document's own language"
    }

    @Test
    fun editNestsFieldAnswersUnderAFieldsObject() {
        // Field answers sit under one "fields" object so a user field cannot collide with the
        // tag contract; each requested key appears there with its type literal.
        val prompt = editPrompt(fields = listOf(spec(FieldKeys.DOC_DATE)))
        prompt shouldContain "\"fields\": {\"docDate\": \"YYYY-MM-DD\"|null}"
        prompt shouldContain "\"fields.docDate\""
    }

    @Test
    fun editFoldsACustomFieldsHintIntoItsRule() {
        // A custom field's hint is the quality lever: it must reach the field's rule verbatim.
        val hinted =
            AiFieldSpec(
                FieldDef(
                    key = "amount",
                    label = "Amount",
                    type = FieldType.NUMBER,
                    showByDefault = false,
                    aiEnabled = false,
                    position = 5,
                    hint = "the grand total including VAT",
                ),
                AiFieldMode.UNFILLED,
            )
        val prompt = editPrompt(fields = listOf(hinted))
        prompt shouldContain "\"fields.amount\" (Amount)"
        prompt shouldContain "the grand total including VAT"
    }

    @Test
    fun editEmitsAFieldsExistingValuesWhenItHasVocabulary() {
        // A TEXT field carrying existing values advertises them for verbatim reuse.
        val prompt =
            editPrompt(fields = listOf(spec(FieldKeys.CORRESPONDENT, vocabulary = listOf("Acme", "Utility Co"))))
        prompt shouldContain "Existing values of correspondent (\"correspondent\") in this library"
        prompt shouldContain "Acme, Utility Co"
    }

    @Test
    fun editCarriesTodayWhenADateIsAskedFor() {
        editPrompt(fields = dateFields) shouldContain "Today is ${dutch.today}"
    }

    @Test
    fun editUsesTodayAsAPlausibilityCheckAndNotABound() {
        val prompt = editPrompt(fields = dateFields)
        // Old paperwork and next year's concert ticket are both real; nothing may be clamped.
        prompt shouldContain "never move a date just to put it in the past"
        prompt shouldContain "plausibility check"
    }

    @Test
    fun editLetsTheDocumentDecideDayMonthOrder() {
        val prompt = editPrompt(fields = dateFields)
        prompt shouldContain "dd/mm/yyyy"
        prompt shouldContain "mm/dd/yyyy"
        // The app's language is the wrong signal: a US invoice stays month-first in a Dutch app.
        prompt shouldContain "the DOCUMENT decides how to read them, not this app's"
    }

    @Test
    fun editSkipsDateGuidanceWhenNoDateIsAskedFor() {
        // Prompt weight is paid per document in a run, so a tags-only run carries no date rules.
        val prompt = editPrompt(AiEditRequest(tags = true))
        prompt shouldNotContain "dd/mm/yyyy"
        prompt shouldNotContain "Today is"
    }

    @Test
    fun editContractCoversOnlyWhatTheRunAskedFor() {
        val prompt = editPrompt(AiEditRequest(tags = true))
        prompt shouldContain "\"add_tags\": [\"tag\"]"
        prompt shouldNotContain "\"remove_tags\""
        prompt shouldNotContain "\"fields\""
    }

    @Test
    fun editCarriesTheTagVocabularyOnlyForTagRuns() {
        editPrompt(AiEditRequest(tags = true)) shouldContain "Existing tags in this library: invoice"
        editPrompt(fields = dateFields) shouldNotContain "Existing tags in this library"
    }

    @Test
    fun editSystemPromptIsNotLeftIndentedByItsInterpolatedSections() {
        // trimIndent() runs after interpolation, so a multi-line interpolated value used to drop
        // the common indent to zero and ship every other line indented. Sections are trimmed
        // individually to prevent that; this guards the fix.
        val prompt = editPrompt(AiEditRequest(tags = true, tagRemovals = true), context = dutch)
        prompt.lines().filter { it.startsWith(" ") } shouldBe emptyList()
    }

    @Test
    fun editStillForbidsInventingFacts() {
        editPrompt(fields = dateFields) shouldContain "never invent facts"
    }
}
