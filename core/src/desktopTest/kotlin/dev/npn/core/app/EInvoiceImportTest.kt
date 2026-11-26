package dev.npn.core.app

import dev.npn.core.einvoice.EInvoiceFormat
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test

/**
 * A PDF carrying a structured e-invoice fills blank document fields at import, never overwrites a
 * value that is already there, never runs again on a rescan, and marks the document for the badge.
 */
class EInvoiceImportTest {
    @Test
    fun importingDoesNotFillDeletedFields() =
        invoiceTest { rig ->
            val keys = setOf(FieldKeys.IBAN, FieldKeys.PAYMENT_REFERENCE)
            val deleted =
                rig.session.fieldRegistry
                    .current()
                    .filter { it.key in keys }
            deleted.size shouldBe 2
            deleted.forEach { rig.session.fieldRegistry.remove(it.key) }

            val imported = importInvoice(rig, "factur-x.xml", "cii.xml")
            val fields = rig.db.documentFieldDao().valuesOf(imported.docId)
            keys.forEach { fields[it].shouldBeNull() }
            fields[FieldKeys.INVOICE_NUMBER] shouldBe "INV-2024-0042"

            deleted.forEach { rig.session.fieldRegistry.upsert(it) }
            val restored = rig.db.documentFieldDao().valuesOf(imported.docId)
            keys.forEach { restored[it].shouldBeNull() }
        }

    @Test
    fun importingACiiInvoiceFillsBlankFields() =
        invoiceTest { rig ->
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
        invoiceTest { rig ->
            val imported = importInvoice(rig, "xrechnung.xml", "ubl.xml")
            val fields = rig.db.documentFieldDao().valuesOf(imported.docId)

            fields[FieldKeys.INVOICE_NUMBER] shouldBe "UBL-2024-77"
            fields[FieldKeys.INVOICE_TOTAL] shouldBe "250.5"
            fields[FieldKeys.INVOICE_CURRENCY] shouldBe "GBP"
            fields[FieldKeys.EINVOICE] shouldBe EInvoiceFormat.UBL.wire
        }

    @Test
    fun incompatibleExtractedValueDoesNotPreventOtherFields() =
        invoiceTest { rig ->
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
        invoiceTest { rig ->
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
        invoiceTest { rig ->
            val imported =
                rig.session
                    .importPdfBytes(PdfFixtures.embeddedTextPdf("ordinary invoice"), "ordinary.pdf")
                    .shouldBeInstanceOf<ImportOutcome.Imported>()

            rig.db
                .documentFieldDao()
                .valuesOf(imported.docId)[FieldKeys.EINVOICE]
                .shouldBeNull()
        }

    /** The badge says the metadata came from an invoice, so an invoice that filled nothing gets none. */
    @Test
    fun anInvoiceThatFillsNothingIsNotMarked() =
        invoiceTest { rig ->
            val registry = rig.session.fieldRegistry
            registry
                .current()
                .filter { it.key != FieldKeys.EINVOICE }
                .forEach { registry.remove(it.key) }

            val imported = importInvoice(rig, "factur-x.xml", "cii.xml")

            rig.db
                .documentFieldDao()
                .valuesOf(imported.docId) shouldBe emptyMap()
        }

    @Test
    fun malformedAndXxeAttachmentsAreIgnored() =
        invoiceTest { rig ->
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
        invoiceTest { rig ->
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
        invoiceTest { rig ->
            val imported = importInvoice(rig, "factur-x.xml", "cii.xml")
            rig.session.meta.setField(imported.docId, FieldKeys.INVOICE_NUMBER, null)

            rig.session.rescan()

            rig.db
                .documentFieldDao()
                .valuesOf(imported.docId)[FieldKeys.INVOICE_NUMBER]
                .shouldBeNull()
        }

    /** An [appSessionTest] in a library with the E-invoice fields on. */
    private fun invoiceTest(body: suspend TestScope.(AppSessionRig) -> Unit) =
        appSessionTest { rig ->
            rig.session.fieldRegistry.setEInvoiceFields(true)
            body(rig)
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
