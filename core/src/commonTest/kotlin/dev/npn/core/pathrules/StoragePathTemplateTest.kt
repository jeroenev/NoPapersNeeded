package dev.npn.core.pathrules

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StoragePathTemplateTest {
    private val context = StoragePathContext(
        title = "Quarterly report",
        docDate = "2026-07-20",
        docType = "Report",
        correspondent = null,
        tags = listOf("work", "finance"),
        originalFileName = "scan.pdf",
    )

    @Test
    fun expandsVariablesFormatsDateAndOmitsEmptyFolder() {
        StoragePathTemplate.expand(
            "{correspondent}/{doc_date:yyyy-MM}/{docType}/{tags}/{title}.pdf",
            context,
        ).getOrThrow() shouldBe "2026-07/Report/finance, work/Quarterly report.pdf"
    }

    @Test
    fun sanitizesMetadataValues() {
        val unsafeTitle = context.copy(title = "invoice/July: final")
        StoragePathTemplate.expand("{title}.pdf", unsafeTitle).getOrThrow() shouldBe
            "invoice_July_ final.pdf"
    }

    @Test
    fun rejectsUnsafeAndInvalidPaths() {
        val templates = listOf(
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
}
