package dev.npn.core.pathrules

import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StoragePathTemplateTest {
    private val context =
        StoragePathContext(
            fields =
                mapOf(
                    FieldKeys.TITLE to "Quarterly report",
                    FieldKeys.DOC_DATE to "2026-07-20",
                    FieldKeys.DOC_TYPE to "Report",
                    "amount" to "42.50",
                    "dueDate" to "2026-08-01",
                ),
            tags = listOf("work", "finance"),
            originalFileName = "scan.pdf",
            fieldTypes =
                FieldKeys.seedDefinitions().associate { it.key to it.type } +
                    mapOf("amount" to FieldType.DECIMAL, "dueDate" to FieldType.DATE),
        )

    @Test
    fun expandsVariablesFormatsDateAndOmitsEmptyFolder() {
        StoragePathTemplate
            .expand(
                "{correspondent}/{doc_date:yyyy-MM}/{docType}/{tags}/{title}.pdf",
                context,
            ).pathOrNull shouldBe "2026-07/Report/finance, work/Quarterly report.pdf"
    }

    @Test
    fun sanitizesMetadataValues() {
        val unsafeTitle = context.copy(fields = context.fields + (FieldKeys.TITLE to "invoice/July: final"))
        StoragePathTemplate.expand("{title}.pdf", unsafeTitle).pathOrNull shouldBe
            "invoice_July_ final.pdf"
    }

    @Test
    fun rejectsUnsafeAndInvalidPathsSayingWhy() {
        val expected =
            mapOf(
                "/root/{title}.pdf" to StoragePathError.Absolute,
                "C:\\root\\{title}.pdf" to StoragePathError.Absolute,
                "c:root/{title}.pdf" to StoragePathError.Absolute,
                "../{title}.pdf" to StoragePathError.Traversal,
                ".npn/{title}.pdf" to StoragePathError.ReservedDirectory(".npn"),
                "bad:name/{title}.pdf" to StoragePathError.InvalidSegment("bad:name"),
                "{correspondent}" to StoragePathError.Blank,
                " " to StoragePathError.Blank,
                "a\\b/{title}.pdf" to StoragePathError.Backslash,
                "a /{title}.pdf" to StoragePathError.EdgeWhitespace,
            )
        expected.forEach { (template, error) ->
            StoragePathTemplate.expand(template, context) shouldBe StoragePathExpansion.Invalid(error)
        }
    }

    @Test
    fun rejectsBrokenPlaceholdersSayingWhere() {
        StoragePathTemplate.expand("a/{title.pdf", context) shouldBe
            StoragePathExpansion.Invalid(StoragePathError.UnclosedPlaceholder(2))
        StoragePathTemplate.expand("a}/{title}.pdf", context) shouldBe
            StoragePathExpansion.Invalid(StoragePathError.UnexpectedClosingBrace(1))
    }

    @Test
    fun rejectsUnknownVariablesAndUnsupportedFormatting() {
        StoragePathTemplate.expand("{unknown}.pdf", context) shouldBe
            StoragePathExpansion.Invalid(StoragePathError.UnknownVariable("unknown"))
        StoragePathTemplate.expand("{title:yyyy}.pdf", context) shouldBe
            StoragePathExpansion.Invalid(StoragePathError.FormatNotAllowed("title"))
        StoragePathTemplate.expand("{tags:yyyy}/{title}.pdf", context) shouldBe
            StoragePathExpansion.Invalid(StoragePathError.FormatNotAllowed("tags"))
    }

    @Test
    fun genericFieldPlaceholderReadsAnyKeyAndFormatsDates() {
        StoragePathTemplate
            .expand(
                "{field:amount}/{field:dueDate:yyyy-MM}/{field:docType}.pdf",
                context,
            ).pathOrNull shouldBe
            "42.50/2026-08/Report.pdf"
    }

    @Test
    fun genericFieldPlaceholderOmitsMissingValuesAndRefusesFormattingNonDates() {
        StoragePathTemplate.expand("{field:vendor}/{title}.pdf", context).pathOrNull shouldBe "Quarterly report.pdf"
        StoragePathTemplate.expand("{field:amount:yyyy}.pdf", context) shouldBe
            StoragePathExpansion.Invalid(StoragePathError.NotADateField("amount"))
        StoragePathTemplate.expand("{field}.pdf", context) shouldBe
            StoragePathExpansion.Invalid(StoragePathError.MissingFieldKey)
        StoragePathTemplate.expand("{field:}.pdf", context) shouldBe
            StoragePathExpansion.Invalid(StoragePathError.MissingFieldKey)
    }

    @Test
    fun titleFallsBackToTheOriginalFileName() {
        val untitled = context.copy(fields = context.fields - FieldKeys.TITLE)
        StoragePathTemplate.expand("{title}.pdf", untitled).pathOrNull shouldBe "scan.pdf"
    }
}
