package dev.npn.core.einvoice

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertNull

class EInvoiceXmlParserTest {
    @Test
    fun parsesACiiInvoice() {
        val invoice = parseEInvoiceXml(fixture("cii.xml")) ?: error("CII fixture did not parse")

        invoice.format shouldBe EInvoiceFormat.CII
        invoice.invoiceNumber shouldBe "INV-2024-0042"
        invoice.issueDate shouldBe "2024-01-15"
        invoice.dueDate shouldBe "2024-02-15"
        invoice.totalAmount shouldBe "121"
        invoice.currency shouldBe "EUR"
        invoice.sellerName shouldBe "ACME Supplies BV"
        invoice.buyerName shouldBe "Jeroen Example"
        invoice.paymentReference shouldBe "RF18539007547034"
        invoice.iban shouldBe "NL91ABNA0417164300"
    }

    @Test
    fun parsesAUblInvoice() {
        val invoice = parseEInvoiceXml(fixture("ubl.xml")) ?: error("UBL fixture did not parse")

        invoice.format shouldBe EInvoiceFormat.UBL
        invoice.invoiceNumber shouldBe "UBL-2024-77"
        invoice.issueDate shouldBe "2024-03-01"
        invoice.dueDate shouldBe "2024-03-31"
        invoice.totalAmount shouldBe "250.5"
        invoice.currency shouldBe "GBP"
        invoice.sellerName shouldBe "Widgets Ltd"
        invoice.buyerName shouldBe "Buyer Corp"
        invoice.paymentReference shouldBe "PAY-REF-9"
        invoice.iban shouldBe "GB33BUKB20201555555555"
    }

    @Test
    fun prepaidUblUsesInvoiceTotalAndDoesNotTreatTermsAsReference() {
        val xml =
            fixture("ubl.xml")
                .decodeToString()
                .replace("<cbc:PaymentID>PAY-REF-9</cbc:PaymentID>", "")
                .replace(
                    "<cac:LegalMonetaryTotal>",
                    "<cac:PaymentTerms><cbc:Note>Pay within 30 days</cbc:Note></cac:PaymentTerms>" +
                        "<cac:LegalMonetaryTotal><cbc:TaxInclusiveAmount currencyID=\"GBP\">300.50" +
                        "</cbc:TaxInclusiveAmount><cbc:PrepaidAmount currencyID=\"GBP\">50</cbc:PrepaidAmount>",
                )
        val invoice = checkNotNull(parseEInvoiceXml(xml.encodeToByteArray()))
        invoice.totalAmount shouldBe "300.5"
        invoice.paymentReference shouldBe null
    }

    @Test
    fun anInvoiceIsNotACreditNote() {
        checkNotNull(parseEInvoiceXml(fixture("cii.xml"))).kind shouldBe EInvoiceKind.INVOICE
        checkNotNull(parseEInvoiceXml(fixture("ubl.xml"))).kind shouldBe EInvoiceKind.INVOICE
    }

    @Test
    fun parsesAUblCreditNote() {
        val note = parseEInvoiceXml(fixture("ubl-credit-note.xml")) ?: error("UBL credit note did not parse")

        note.format shouldBe EInvoiceFormat.UBL
        note.kind shouldBe EInvoiceKind.CREDIT_NOTE
        note.invoiceNumber shouldBe "CN-2024-5"
        note.issueDate shouldBe "2024-04-02"
        note.totalAmount shouldBe "40"
        note.sellerName shouldBe "Widgets Ltd"
        note.buyerName shouldBe "Buyer Corp"
    }

    @Test
    fun readsTheCiiTypeCode() {
        val xml = fixture("cii.xml").decodeToString()
        for ((code, kind) in listOf("380" to EInvoiceKind.INVOICE, "381" to EInvoiceKind.CREDIT_NOTE)) {
            val typed =
                xml.replace(
                    "<ram:ID>INV-2024-0042</ram:ID>",
                    "<ram:ID>N-1</ram:ID><ram:TypeCode>$code</ram:TypeCode>",
                )
            checkNotNull(parseEInvoiceXml(typed.encodeToByteArray())).kind shouldBe kind
        }
    }

    @Test
    fun rejectsMalformedXml() {
        assertNull(parseEInvoiceXml(fixture("malformed.xml")))
    }

    @Test
    fun rejectsAnExternalEntityAttempt() {
        assertNull(parseEInvoiceXml(fixture("xxe.xml")))
    }

    @Test
    fun rejectsADoctypeInAnyCase() {
        val xml = fixture("ubl.xml").decodeToString().replaceFirst("?>", "?><!doctype Invoice>")
        assertNull(parseEInvoiceXml(xml.encodeToByteArray()))
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/einvoice/$name")) { "missing fixture $name" }
            .use { it.readBytes() }
}
