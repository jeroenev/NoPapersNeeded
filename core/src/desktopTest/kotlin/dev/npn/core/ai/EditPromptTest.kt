package dev.npn.core.ai

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

// A prompt's date must be assertable, so every context in this file is built on a fixed clock.

/** The AI-edit flow's system prompt: rules, reply contract, language and date guidance. */
class EditPromptTest {
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
        prompt shouldContain "\"fields.title\" (title): the title, written for this document in Dutch (nl)"
        prompt shouldNotContain "in the document's own language"
    }

    /** What the seeded fields are asked for is their definitions' data: the flag and the hint. */
    @Test
    fun editAsksForAPerDocumentFieldByItsFlagAndHint() {
        val summary =
            FieldDef(
                key = "summary",
                label = "Summary",
                type = FieldType.TEXT,
                showByDefault = false,
                aiEnabled = true,
                position = 5,
                hint = "one line about what it says",
                perDocument = true,
            )
        val prompt = editPrompt(fields = listOf(AiFieldSpec(summary, AiFieldMode.UNFILLED)))
        prompt shouldContain
            "\"fields.summary\" (Summary): the Summary, written for this document in Dutch (nl)"
        prompt shouldContain "one line about what it says"

        // A title whose definition lost the flag is asked for like any text field.
        val plainTitle = AiFieldSpec(seeds.getValue(FieldKeys.TITLE).copy(perDocument = false), AiFieldMode.UNFILLED)
        editPrompt(fields = listOf(plainTitle)) shouldNotContain "written for this document"
    }

    @Test
    fun editCarriesTheSeededHints() {
        val prompt = editPrompt(fields = listOf(spec(FieldKeys.CORRESPONDENT), spec(FieldKeys.DOC_DATE)))
        prompt shouldContain "never the archive's owner"
        prompt shouldContain "not when it was scanned"
    }

    @Test
    fun editFallsBackToTheDocumentsLanguageForATitleWhenTheAppLanguageIsUnknown() {
        val prompt = editPrompt(fields = listOf(spec(FieldKeys.TITLE)), context = promptContext(null))
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
