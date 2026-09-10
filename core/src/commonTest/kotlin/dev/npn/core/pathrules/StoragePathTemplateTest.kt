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
            ).getOrThrow() shouldBe "2026-07/Report/finance, work/Quarterly report.pdf"
    }

    @Test
    fun sanitizesMetadataValues() {
        val unsafeTitle = context.copy(fields = context.fields + (FieldKeys.TITLE to "invoice/July: final"))
        StoragePathTemplate.expand("{title}.pdf", unsafeTitle).getOrThrow() shouldBe
            "invoice_July_ final.pdf"
    }

    @Test
    fun rejectsUnsafeAndInvalidPaths() {
        val templates =
            listOf(
                "/root/{title}.pdf",
                "C:\\root\\{title}.pdf",
                "../{title}.pdf",
                ".npn/{title}.pdf",
                "bad:name/{title}.pdf",
                "{correspondent}",
            )
        templates.forEach { template ->
            StoragePathTemplate.expand(template, context).isFailure shouldBe true
        }
    }

    @Test
    fun rejectsUnknownVariablesAndUnsupportedFormatting() {
        StoragePathTemplate.expand("{unknown}.pdf", context).isFailure shouldBe true
        StoragePathTemplate.expand("{title:yyyy}.pdf", context).isFailure shouldBe true
    }

    @Test
    fun genericFieldPlaceholderReadsAnyKeyAndFormatsDates() {
        StoragePathTemplate
            .expand(
                "{field:amount}/{field:dueDate:yyyy-MM}/{field:docType}.pdf",
                context,
            ).getOrThrow() shouldBe
            "42.50/2026-08/Report.pdf"
    }

    @Test
    fun genericFieldPlaceholderOmitsMissingValuesAndRefusesFormattingNonDates() {
        StoragePathTemplate.expand("{field:vendor}/{title}.pdf", context).getOrThrow() shouldBe "Quarterly report.pdf"
        StoragePathTemplate.expand("{field:amount:yyyy}.pdf", context).isFailure shouldBe true
        StoragePathTemplate.expand("{field}.pdf", context).isFailure shouldBe true
        StoragePathTemplate.expand("{field:}.pdf", context).isFailure shouldBe true
    }

    @Test
    fun titleFallsBackToTheOriginalFileName() {
        val untitled = context.copy(fields = context.fields - FieldKeys.TITLE)
        StoragePathTemplate.expand("{title}.pdf", untitled).getOrThrow() shouldBe "scan.pdf"
    }
}
