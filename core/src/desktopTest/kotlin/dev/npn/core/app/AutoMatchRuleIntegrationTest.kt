package dev.npn.core.app

import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AutoMatchRuleIntegrationTest {

    @Test
    fun ruleAppliesAfterOcrFinishes() = runTest {
        val rig = AppSessionRig.create(backgroundScope)
        try {
            rig.session.start(backgroundScope)
            rig.session.rules.save(
                AutoMatchRule(
                    id = "",
                    name = "Invoice rule",
                    field = RuleField.CONTENT,
                    algorithm = MatchAlgorithm.ANY_WORD,
                    pattern = "invoice",
                    tags = listOf("invoice"),
                    correspondent = "ACME",
                    docType = "Invoice",
                ),
            )

            rig.writePdf("inv.pdf", PdfFixtures.embeddedTextPdf("Invoice from ACME Corporation"))
            rig.session.rescan()

            val id = rig.fs.sha256("inv.pdf")
            rig.session.ocrStatus.first { it.pending == 0 && !it.running }

            // Rule application runs in the post-OCR onTextIndexed callback, which fires after the
            // queue is marked done, so wait for the rule-produced metadata rather than mere
            // document existence.
            val item = rig.session.documents.observeDoc(id)
                .first { it != null && it.tags.contains("invoice") }
                .shouldNotBeNull()
            item.tags shouldContain "invoice"
            item.doc.correspondent shouldBe "ACME"
            item.doc.docType shouldBe "Invoice"
        } finally {
            rig.close()
        }
    }

    @Test
    fun savingRuleAppliesToExistingDocumentsWithText() = runTest {
        val rig = AppSessionRig.create(backgroundScope)
        try {
            rig.session.start(backgroundScope)
            rig.writePdf("bill.pdf", PdfFixtures.embeddedTextPdf("Utility bill for July"))
            rig.session.rescan()

            val id = rig.fs.sha256("bill.pdf")
            rig.session.ocrStatus.first { it.pending == 0 && !it.running }

            rig.session.rules.save(
                AutoMatchRule(
                    id = "",
                    name = "Utility rule",
                    field = RuleField.CONTENT,
                    algorithm = MatchAlgorithm.EXACT,
                    pattern = "Utility bill",
                    tags = listOf("utilities", "bill"),
                    docType = "Bill",
                ),
            )

            val item = rig.session.documents.observeDoc(id).first { it != null }.shouldNotBeNull()
            item.tags shouldContain "utilities"
            item.tags shouldContain "bill"
            item.doc.docType shouldBe "Bill"
        } finally {
            rig.close()
        }
    }

    @Test
    fun disabledRuleDoesNotApply() = runTest {
        val rig = AppSessionRig.create(backgroundScope)
        try {
            rig.session.start(backgroundScope)
            val ruleId = rig.session.rules.save(
                AutoMatchRule(
                    id = "",
                    name = "Disabled rule",
                    enabled = false,
                    algorithm = MatchAlgorithm.ANY_WORD,
                    pattern = "tagme",
                    tags = listOf("tagged"),
                ),
            )

            rig.writePdf("doc.pdf", PdfFixtures.embeddedTextPdf("tagme here"))
            rig.session.rescan()

            val id = rig.fs.sha256("doc.pdf")
            rig.session.ocrStatus.first { it.pending == 0 && !it.running }

            val item = rig.session.documents.observeDoc(id).first { it != null }.shouldNotBeNull()
            item.tags shouldBe emptyList()

            rig.session.rules.setEnabled(ruleId, true)
            val reenabled = rig.session.documents.observeDoc(id).first { it != null }.shouldNotBeNull()
            reenabled.tags shouldContain "tagged"
        } finally {
            rig.close()
        }
    }
}
