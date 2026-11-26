package dev.npn.core.einvoice

import dev.npn.core.model.FieldKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EInvoiceDataTest {
    private val invoice =
        EInvoiceData(
            format = EInvoiceFormat.CII,
            invoiceNumber = "INV-1",
            issueDate = "2024-01-15",
            dueDate = "2024-02-15",
            totalAmount = "121",
            currency = "EUR",
            sellerName = "ACME Supplies BV",
            buyerName = "Jeroen Example",
            paymentReference = "RF18",
            iban = "NL91ABNA0417164300",
        )

    @Test
    fun anInvoiceFillsEveryFieldItNamesAndTheSellerIsTheCorrespondent() {
        val fields = invoice.documentFields()

        assertEquals("ACME Supplies BV", fields[FieldKeys.CORRESPONDENT])
        assertEquals("2024-02-15", fields[FieldKeys.INVOICE_DUE_DATE])
        assertEquals("NL91ABNA0417164300", fields[FieldKeys.IBAN])
        assertEquals("121", fields[FieldKeys.INVOICE_TOTAL])
        // The marker is the caller's to write, once something was filled or held.
        assertFalse(FieldKeys.EINVOICE in fields)
    }

    @Test
    fun aSellerTheLibraryKnowsKeepsTheLibrarysSpelling() {
        val fields = invoice.documentFields(knownCorrespondents = listOf("Acme Supplies", "Other"))

        assertEquals("Acme Supplies", fields[FieldKeys.CORRESPONDENT])
    }

    /** The buyer is a known correspondent and the seller is not: likely an invoice the user issued. */
    @Test
    fun aKnownBuyerAndAnUnknownSellerNameNoCorrespondent() {
        val fields = invoice.documentFields(knownCorrespondents = listOf("jeroen  example"))

        assertNull(fields[FieldKeys.CORRESPONDENT])
        assertEquals("Jeroen Example", fields[FieldKeys.INVOICE_BUYER])
        assertEquals("INV-1", fields[FieldKeys.INVOICE_NUMBER])
    }

    @Test
    fun aSellerKnownToTheLibraryWinsEvenWhenTheBuyerIsKnownToo() {
        val fields = invoice.documentFields(knownCorrespondents = listOf("Jeroen Example", "ACME Supplies B.V."))

        assertEquals("ACME Supplies B.V.", fields[FieldKeys.CORRESPONDENT])
    }

    /** A credit note asks nothing to be paid: no due date for a reminder, no account, a negative total. */
    @Test
    fun aCreditNoteIsNotSomethingToPay() {
        val fields = invoice.copy(kind = EInvoiceKind.CREDIT_NOTE).documentFields()

        assertNull(fields[FieldKeys.INVOICE_DUE_DATE])
        assertNull(fields[FieldKeys.IBAN])
        assertNull(fields[FieldKeys.PAYMENT_REFERENCE])
        assertEquals("-121", fields[FieldKeys.INVOICE_TOTAL])
        assertEquals("INV-1", fields[FieldKeys.INVOICE_NUMBER])
        assertEquals("ACME Supplies BV", fields[FieldKeys.CORRESPONDENT])
    }

    @Test
    fun aCreditNoteAlreadyStatedNegativeStaysNegative() {
        val fields = invoice.copy(kind = EInvoiceKind.CREDIT_NOTE, totalAmount = "-121").documentFields()

        assertEquals("-121", fields[FieldKeys.INVOICE_TOTAL])
    }
}
