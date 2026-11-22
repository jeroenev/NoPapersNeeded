package dev.npn.core.einvoice

import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.EmbeddedXmlFile

/** The two structured e-invoice formats this app reads: CII (ZUGFeRD/Factur-X) and UBL (XRechnung). */
enum class EInvoiceFormat(
    val wire: String,
) {
    CII("cii"),
    UBL("ubl"),
}

/**
 * The metadata a structured e-invoice carries that maps to document fields. Dates are canonical
 * `yyyy-MM-dd`, [totalAmount] is a canonical decimal, and [currency] is a three-letter code. Nothing
 * here is validated beyond its canonical form: tax breakdowns and totals are the seller's numbers,
 * never recomputed.
 */
data class EInvoiceData(
    val format: EInvoiceFormat,
    val invoiceNumber: String? = null,
    val issueDate: String? = null,
    val dueDate: String? = null,
    val totalAmount: String? = null,
    val currency: String? = null,
    val sellerName: String? = null,
    val buyerName: String? = null,
    val paymentReference: String? = null,
    val iban: String? = null,
) {
    /** Whether the invoice named anything at all; a document element alone is not an e-invoice. */
    val isEmpty: Boolean
        get() =
            listOf(
                invoiceNumber,
                issueDate,
                dueDate,
                totalAmount,
                currency,
                sellerName,
                buyerName,
                paymentReference,
                iban,
            ).all { it == null }

    /**
     * The registry values this invoice fills, keyed by [FieldKeys]. Only what is present is included;
     * the marker is always there so the detail screen can show that the metadata came from one.
     */
    fun documentFields(): Map<String, String> =
        buildMap {
            invoiceNumber?.let { put(FieldKeys.INVOICE_NUMBER, it) }
            issueDate?.let { put(FieldKeys.DOC_DATE, it) }
            dueDate?.let { put(FieldKeys.INVOICE_DUE_DATE, it) }
            totalAmount?.let { put(FieldKeys.INVOICE_TOTAL, it) }
            currency?.let { put(FieldKeys.INVOICE_CURRENCY, it) }
            sellerName?.let { put(FieldKeys.CORRESPONDENT, it) }
            buyerName?.let { put(FieldKeys.INVOICE_BUYER, it) }
            paymentReference?.let { put(FieldKeys.PAYMENT_REFERENCE, it) }
            iban?.let { put(FieldKeys.IBAN, it) }
            put(FieldKeys.EINVOICE, format.wire)
        }
}

/**
 * Finds the e-invoice among a PDF's embedded XML files. A known Factur-X/ZUGFeRD/XRechnung name is
 * tried first, then every other XML attachment; the first one whose root is a CII or UBL invoice with
 * at least one value wins.
 */
object EInvoiceDetector {
    private val preferredNames = setOf("factur-x.xml", "zugferd-invoice.xml", "xrechnung.xml")

    fun detect(files: List<EmbeddedXmlFile>): EInvoiceData? {
        val ordered = files.sortedBy { if (it.name.lowercase() in preferredNames) 0 else 1 }
        for (file in ordered) {
            val data = parseEInvoiceXml(file.bytes) ?: continue
            if (!data.isEmpty) return data
        }
        return null
    }
}

/**
 * Read [xml] as a CII (`rsm:CrossIndustryInvoice`) or UBL (`Invoice`) e-invoice, or null when it is
 * neither, is malformed, or tries to pull in a DTD or an external entity. The parser never resolves
 * external resources and never expands a DOCTYPE.
 */
expect fun parseEInvoiceXml(xml: ByteArray): EInvoiceData?
