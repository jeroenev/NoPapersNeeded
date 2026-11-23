package dev.npn.core.app

import dev.npn.core.einvoice.EInvoiceFormat
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * A PDF carrying a structured e-invoice fills blank document fields at import, never overwrites a
 * value that is already there, never runs again on a rescan, and marks the document for the badge.
 */
class EInvoiceImportTest {
    @Test
    fun importingACiiInvoiceFillsBlankFields() =
        appSessionTest { rig ->
            val imported = importInvoice(rig, "factur-x.xml", "cii.xml")
            val fields = rig.db.documentFieldDao().valuesOf(imported.docId)

            fields[FieldKeys.INVOICE_NUMBER] shouldBe "INV-2024-0042"
            fields[FieldKeys.DOC_DATE] shouldBe "2024-01-15"
            fields[FieldKeys.INVOICE_DUE_DATE] shouldBe "2024-02-15"
            fields[FieldKeys.INVOICE_TOTAL] shouldBe "121"
            fields[FieldKeys.INVOICE_CURRENCY] shouldBe "EUR"
            fields[FieldKeys.CORRESPONDENT] shouldBe "ACME Supplies BV"
            fields[FieldKeys.INVOICE_BUYER] shouldBe "Jeroen Example"
            fields[FieldKeys.PAYMENT_REFERENCE] shouldBe "RF18539007547034"
            fields[FieldKeys.IBAN] shouldBe "NL91ABNA0417164300"
            fields[FieldKeys.EINVOICE] shouldBe EInvoiceFormat.CII.wire
        }

    @Test
    fun importingAUblInvoiceFillsBlankFields() =
        appSessionTest { rig ->
            val imported = importInvoice(rig, "xrechnung.xml", "ubl.xml")
            val fields = rig.db.documentFieldDao().valuesOf(imported.docId)

            fields[FieldKeys.INVOICE_NUMBER] shouldBe "UBL-2024-77"
            fields[FieldKeys.INVOICE_TOTAL] shouldBe "250.5"
            fields[FieldKeys.INVOICE_CURRENCY] shouldBe "GBP"
            fields[FieldKeys.EINVOICE] shouldBe EInvoiceFormat.UBL.wire
        }

    @Test
    fun incompatibleExtractedValueDoesNotPreventOtherFields() =
        appSessionTest { rig ->
            val def =
                rig.session.fieldRegistry
                    .current()
                    .single { it.key == FieldKeys.INVOICE_NUMBER }
            rig.session.fieldRegistry.upsert(def.copy(type = FieldType.NUMBER))
            val imported = importInvoice(rig, "factur-x.xml", "cii.xml")
            val fields = rig.db.documentFieldDao().valuesOf(imported.docId)
            fields[FieldKeys.INVOICE_NUMBER].shouldBeNull()
            fields[FieldKeys.INVOICE_TOTAL] shouldBe "121"
            fields[FieldKeys.EINVOICE] shouldBe EInvoiceFormat.CII.wire
        }

    @Test
    fun filledRetypedFieldDoesNotPreventFillingOtherFieldsOnReturn() =
        appSessionTest { rig ->
            val bytes = invoicePdf("factur-x.xml", "cii.xml")
            val first = rig.session.importPdfBytes(bytes, "invoice.pdf").shouldBeInstanceOf<ImportOutcome.Imported>()
            rig.session.meta.setField(first.docId, FieldKeys.INVOICE_NUMBER, "123")
            val def =
                rig.session.fieldRegistry
                    .current()
                    .single { it.key == FieldKeys.INVOICE_NUMBER }
            rig.session.fieldRegistry.upsert(def.copy(type = FieldType.NUMBER))
            rig.session.meta.setField(first.docId, FieldKeys.INVOICE_TOTAL, null)
            rig.session.docLifecycle.trashDoc(first.docId)
            val again = rig.session.importPdfBytes(bytes, "invoice.pdf").shouldBeInstanceOf<ImportOutcome.Imported>()
            val fields = rig.db.documentFieldDao().valuesOf(again.docId)
            fields[FieldKeys.INVOICE_NUMBER] shouldBe "123"
            fields[FieldKeys.INVOICE_TOTAL] shouldBe "121"
        }

    @Test
    fun aPdfWithoutAnInvoiceIsNotMarked() =
        appSessionTest { rig ->
            val imported =
                rig.session
                    .importPdfBytes(PdfFixtures.embeddedTextPdf("ordinary invoice"), "ordinary.pdf")
                    .shouldBeInstanceOf<ImportOutcome.Imported>()

            rig.db
                .documentFieldDao()
                .valuesOf(imported.docId)[FieldKeys.EINVOICE]
                .shouldBeNull()
        }

    @Test
    fun malformedAndXxeAttachmentsAreIgnored() =
        appSessionTest { rig ->
            val malformed = importInvoice(rig, "factur-x.xml", "malformed.xml")
            rig.db
                .documentFieldDao()
                .valuesOf(malformed.docId)[FieldKeys.EINVOICE]
                .shouldBeNull()

            val xxe = importInvoice(rig, "factur-x.xml", "xxe.xml")
            rig.db
                .documentFieldDao()
                .valuesOf(xxe.docId)[FieldKeys.EINVOICE]
                .shouldBeNull()
        }

    @Test
    fun aValueWrittenBeforeTheReturnIsNotOverwritten() =
        appSessionTest { rig ->
            val bytes = invoicePdf("factur-x.xml", "cii.xml")
            val first = rig.session.importPdfBytes(bytes, "invoice.pdf").shouldBeInstanceOf<ImportOutcome.Imported>()
            rig.session.meta.setField(first.docId, FieldKeys.CORRESPONDENT, "A person chose this")
            // A blank field is fair game when the document comes back through an import.
            rig.session.meta.setField(first.docId, FieldKeys.INVOICE_NUMBER, null)
            rig.session.docLifecycle.trashDoc(first.docId)

            val again = rig.session.importPdfBytes(bytes, "invoice.pdf").shouldBeInstanceOf<ImportOutcome.Imported>()

            val fields = rig.db.documentFieldDao().valuesOf(again.docId)
            fields[FieldKeys.CORRESPONDENT] shouldBe "A person chose this"
            fields[FieldKeys.INVOICE_NUMBER] shouldBe "INV-2024-0042"
        }

    @Test
    fun aRescanDoesNotApplyTheInvoiceAgain() =
        appSessionTest { rig ->
            val imported = importInvoice(rig, "factur-x.xml", "cii.xml")
            rig.session.meta.setField(imported.docId, FieldKeys.INVOICE_NUMBER, null)

            rig.session.rescan()

            rig.db
                .documentFieldDao()
                .valuesOf(imported.docId)[FieldKeys.INVOICE_NUMBER]
                .shouldBeNull()
        }

    private suspend fun importInvoice(
        rig: AppSessionRig,
        attachmentName: String,
        fixture: String,
    ): ImportOutcome.Imported =
        rig.session
            .importPdfBytes(invoicePdf(attachmentName, fixture), "invoice.pdf")
            .shouldBeInstanceOf<ImportOutcome.Imported>()

    private fun invoicePdf(
        attachmentName: String,
        fixture: String,
    ): ByteArray =
        PdfFixtures.pdfWithEmbeddedFile(
            attachmentName,
            checkNotNull(javaClass.getResourceAsStream("/einvoice/$fixture")) { "missing fixture $fixture" }
                .use { it.readBytes() },
        )
}
