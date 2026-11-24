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
    fun rejectsMalformedXml() {
        assertNull(parseEInvoiceXml(fixture("malformed.xml")))
    }

    @Test
    fun rejectsAnExternalEntityAttempt() {
        assertNull(parseEInvoiceXml(fixture("xxe.xml")))
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/einvoice/$name")) { "missing fixture $name" }
            .use { it.readBytes() }
}
