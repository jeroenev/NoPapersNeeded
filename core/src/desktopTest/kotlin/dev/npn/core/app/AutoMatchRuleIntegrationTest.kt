package dev.npn.core.app

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.AutoMatchRuleJson
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
    fun ruleAppliesAfterOcrFinishes() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.session.start()
                rig.session.rules.save(
                    AutoMatchRule(
                        id = "",
                        name = "Invoice rule",
                        field = RuleField.Content,
                        algorithm = MatchAlgorithm.ANY_WORD,
                        pattern = "invoice",
                        tags = listOf("invoice"),
                        sets = mapOf(FieldKeys.CORRESPONDENT to "ACME", FieldKeys.DOC_TYPE to "Invoice"),
                    ),
                )

                rig.writePdf("inv.pdf", PdfFixtures.embeddedTextPdf("Invoice from ACME Corporation"))
                rig.session.rescan()

                val id = rig.fs.sha256("inv.pdf")
                rig.session.ocr.ocrStatus
                    .first { it.pending == 0 && !it.running }

                // Rule application runs in the post-OCR onTextIndexed callback, which fires after the
                // queue is marked done, so wait for the rule-produced metadata rather than mere
                // document existence.
                val item =
                    rig.session.documents
                        .observeDoc(id)
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
    fun savingRuleAppliesToExistingDocumentsWithText() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.session.start()
                rig.writePdf("bill.pdf", PdfFixtures.embeddedTextPdf("Utility bill for July"))
                rig.session.rescan()

                val id = rig.fs.sha256("bill.pdf")
                rig.session.ocr.ocrStatus
                    .first { it.pending == 0 && !it.running }

                rig.session.rules.save(
                    AutoMatchRule(
                        id = "",
                        name = "Utility rule",
                        field = RuleField.Content,
                        algorithm = MatchAlgorithm.EXACT,
                        pattern = "Utility bill",
                        tags = listOf("utilities", "bill"),
                        sets = mapOf(FieldKeys.DOC_TYPE to "Bill"),
                    ),
                )

                val item =
                    rig.session.documents
                        .observeDoc(id)
                        .first { it != null }
                        .shouldNotBeNull()
                item.tags shouldContain "utilities"
                item.tags shouldContain "bill"
                item.doc.docType shouldBe "Bill"
            } finally {
                rig.close()
            }
        }

    @Test
    fun disabledRuleDoesNotApply() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.session.start()
                val ruleId =
                    rig.session.rules.save(
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
                rig.session.ocr.ocrStatus
                    .first { it.pending == 0 && !it.running }

                val item =
                    rig.session.documents
                        .observeDoc(id)
                        .first { it != null }
                        .shouldNotBeNull()
                item.tags shouldBe emptyList()

                rig.session.rules.setEnabled(ruleId, true)
                val reenabled =
                    rig.session.documents
                        .observeDoc(id)
                        .first { it != null }
                        .shouldNotBeNull()
                reenabled.tags shouldContain "tagged"
            } finally {
                rig.close()
            }
        }

    @Test
    fun ruleOnAFieldSetsAnyRegistryFieldAndFiresOnceThenGoesQuiet() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.session.start()
                rig.writePdf("doc.pdf", PdfFixtures.embeddedTextPdf("plain text"))
                rig.session.rescan()
                val id = rig.fs.sha256("doc.pdf")
                rig.session.ocr.ocrStatus
                    .first { it.pending == 0 && !it.running }
                rig.session.fieldRegistry.upsert(
                    FieldDef("dueDate", "Due", FieldType.DATE, showByDefault = false, aiEnabled = false, position = 9),
                )
                rig.session.meta.setField(id, FieldKeys.CORRESPONDENT, "ACME Energy")

                rig.session.rules.save(
                    AutoMatchRule(
                        id = "",
                        name = "Correspondent rule",
                        field = RuleField.Field(FieldKeys.CORRESPONDENT),
                        algorithm = MatchAlgorithm.ANY_WORD,
                        pattern = "acme",
                        // A value the field's type refuses is skipped, never written off-type.
                        sets = mapOf("dueDate" to "2026-09-30", FieldKeys.DOC_DATE to "not a date"),
                    ),
                )
                val item =
                    rig.session.documents
                        .observeDoc(id)
                        .first { it?.doc?.fields?.containsKey("dueDate") == true }
                        .shouldNotBeNull()
                item.doc.fields["dueDate"] shouldBe "2026-09-30"
                item.doc.docDate shouldBe null

                // P16: the gate is "value differs", so a second pass writes nothing.
                val before = rig.journalObjectCount()
                rig.session.rules.setEnabled(
                    rig.session.rules
                        .observeRules()
                        .first()
                        .single()
                        .id,
                    true,
                )
                rig.journalObjectCount() shouldBe before
            } finally {
                rig.close()
            }
        }

    @Test
    fun legacyRuleJsonDecodesNamedColumnsIntoSets() {
        val legacy =
            """[{"id":"r1","name":"Old","field":"TITLE","algorithm":"EXACT","pattern":"x",""" +
                """"tags":["t"],"correspondent":"ACME","docType":" Invoice ","position":2}]"""
        val rules = AutoMatchRuleJson.decodeList(legacy)
        rules.single() shouldBe
            AutoMatchRule(
                id = "r1",
                name = "Old",
                field = RuleField.Field(FieldKeys.TITLE),
                algorithm = MatchAlgorithm.EXACT,
                pattern = "x",
                tags = listOf("t"),
                sets = mapOf(FieldKeys.CORRESPONDENT to "ACME", FieldKeys.DOC_TYPE to "Invoice"),
                position = 2,
            )
        // The current shape round-trips through the same codec.
        AutoMatchRuleJson.decodeList(AutoMatchRuleJson.encodeList(rules)) shouldBe rules
    }
}
