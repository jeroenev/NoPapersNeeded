package dev.npn.core.app

import dev.npn.core.ai.EditRunSource
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.einvoice.EInvoiceFixtures
import dev.npn.core.einvoice.EInvoiceFormat
import dev.npn.core.mail.MailImportResult
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test

/**
 * Anybody can mail an invoice, so what an emailed one says waits for the user: its values are held
 * as a review on the processing queue instead of written, and the review writes what the user keeps.
 */
class EInvoiceMailReviewTest {
    @Test
    fun anEmailedInvoiceIsHeldForReviewNotWritten() =
        invoiceTest { rig ->
            val docId = importFromMail(rig)

            val fields = rig.db.documentFieldDao().valuesOf(docId)
            listOf(
                FieldKeys.CORRESPONDENT,
                FieldKeys.IBAN,
                FieldKeys.PAYMENT_REFERENCE,
                FieldKeys.INVOICE_DUE_DATE,
                FieldKeys.INVOICE_NUMBER,
            ).forEach { fields[it].shouldBeNull() }
            // Something is held, so the document says where its review came from.
            fields[FieldKeys.EINVOICE] shouldBe EInvoiceFormat.CII.wire

            val run =
                rig.session.work.editRuns
                    .first()
                    .single()
            run.source shouldBe EditRunSource.EINVOICE
            run.total shouldBe 1
            run.state shouldBe dev.npn.core.app.WorkState.READY

            val review = checkNotNull(rig.session.aiEditQueue.openEditRun(run.jobId))
            val held =
                review.docs
                    .single()
                    .fields
                    .associate { it.key to it.value }
            held shouldContainAll
                mapOf(
                    FieldKeys.CORRESPONDENT to "ACME Supplies BV",
                    FieldKeys.IBAN to "NL91ABNA0417164300",
                    FieldKeys.INVOICE_DUE_DATE to "2024-02-15",
                    FieldKeys.INVOICE_NUMBER to "INV-2024-0042",
                )

            review.apply(review.docs).shouldBeInstanceOf<ApplyOutcome.Done>()

            val applied = rig.db.documentFieldDao().valuesOf(docId)
            applied[FieldKeys.IBAN] shouldBe "NL91ABNA0417164300"
            applied[FieldKeys.CORRESPONDENT] shouldBe "ACME Supplies BV"
            rig.session.work.editRuns
                .first()
                .shouldBeEmpty()
        }

    @Test
    fun theHeldSellerTakesTheSpellingOfACorrespondentTheLibraryKnows() =
        invoiceTest { rig ->
            rig.writePdf(
                "letter.pdf",
                dev.npn.core.pdf.PdfFixtures
                    .embeddedTextPdf("a letter"),
            )
            rig.session.rescan()
            rig.session.meta.setField(rig.docId("letter.pdf"), FieldKeys.CORRESPONDENT, "Acme Supplies")

            importFromMail(rig)

            val run =
                rig.session.work.editRuns
                    .first()
                    .single()
            val review = checkNotNull(rig.session.aiEditQueue.openEditRun(run.jobId))
            review.docs
                .single()
                .fields
                .single { it.key == FieldKeys.CORRESPONDENT }
                .value shouldBe "Acme Supplies"
        }

    @Test
    fun withTheSettingOffAnEmailedInvoiceHoldsNothing() =
        invoiceTest { rig ->
            rig.session.fieldRegistry.setEInvoiceFields(false)

            val docId = importFromMail(rig)

            rig.session.work.editRuns
                .first()
                .shouldBeEmpty()
            rig.db
                .documentFieldDao()
                .valuesOf(docId)[FieldKeys.EINVOICE]
                .shouldBeNull()
        }

    /** Nothing left to offer (every field it names is gone): no review, and no badge. */
    @Test
    fun anEmailedInvoiceWithNothingToOfferIsNeitherHeldNorMarked() =
        invoiceTest { rig ->
            val registry = rig.session.fieldRegistry
            registry
                .current()
                .filter { it.key != FieldKeys.EINVOICE }
                .forEach { registry.remove(it.key) }

            val docId = importFromMail(rig)

            rig.session.work.editRuns
                .first()
                .shouldHaveSize(0)
            rig.db
                .documentFieldDao()
                .valuesOf(docId)[FieldKeys.EINVOICE]
                .shouldBeNull()
        }

    private suspend fun importFromMail(rig: AppSessionRig): String =
        rig.session.mailSink
            .importPdf(EInvoiceFixtures.pdf("factur-x.xml", "cii.xml"), "invoice.pdf")
            .shouldBeInstanceOf<MailImportResult.Imported>()
            .docId

    /** An [appSessionTest] in a library with the E-invoice fields on. */
    private fun invoiceTest(body: suspend TestScope.(AppSessionRig) -> Unit) =
        appSessionTest { rig ->
            rig.session.fieldRegistry.setEInvoiceFields(true)
            body(rig)
        }
}
