package dev.npn.core.einvoice

import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfEngine

/** The two structured e-invoice formats this app reads: CII (ZUGFeRD/Factur-X) and UBL (XRechnung). */
enum class EInvoiceFormat(
    val wire: String,
) {
    CII("cii"),
    UBL("ubl"),
}

/** What a structured e-invoice asks for: payment ([INVOICE]), or a credit to the buyer ([CREDIT_NOTE]). */
enum class EInvoiceKind {
    INVOICE,
    CREDIT_NOTE,
}

/**
 * The metadata a structured e-invoice carries that maps to document fields. Dates are canonical
 * `yyyy-MM-dd`, [totalAmount] is a canonical decimal, and [currency] is a three-letter code. Nothing
 * here is validated beyond its canonical form: tax breakdowns and totals are the seller's numbers,
 * never recomputed.
 */
data class EInvoiceData(
    val format: EInvoiceFormat,
    val kind: EInvoiceKind = EInvoiceKind.INVOICE,
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
     * The registry values this invoice fills, keyed by [FieldKeys]; only what is present is included.
     * The [FieldKeys.EINVOICE] marker is not among them: it is written once one of these was filled
     * or held for review, by whoever does that.
     *
     * The correspondent is the other party, and the library may not know which party its user is: the
     * seller, in the spelling of [knownCorrespondents] when the library already has them, except when
     * only the buyer is among them. Then the user most likely issued this invoice to a known client,
     * and the seller is the user's own company, so no correspondent is offered.
     *
     * A credit note asks nothing to be paid: its total is negative, and it offers no due date (which
     * a reminder would watch), payment reference or account.
     */
    fun documentFields(knownCorrespondents: Collection<String> = emptyList()): Map<String, String> {
        val payable = kind == EInvoiceKind.INVOICE
        return buildMap {
            invoiceNumber?.let { put(FieldKeys.INVOICE_NUMBER, it) }
            issueDate?.let { put(FieldKeys.DOC_DATE, it) }
            dueDate?.takeIf { payable }?.let { put(FieldKeys.INVOICE_DUE_DATE, it) }
            totalAmount?.let { put(FieldKeys.INVOICE_TOTAL, if (payable) it else negative(it)) }
            currency?.let { put(FieldKeys.INVOICE_CURRENCY, it) }
            correspondent(knownCorrespondents)?.let { put(FieldKeys.CORRESPONDENT, it) }
            buyerName?.let { put(FieldKeys.INVOICE_BUYER, it) }
            paymentReference?.takeIf { payable }?.let { put(FieldKeys.PAYMENT_REFERENCE, it) }
            iban?.takeIf { payable }?.let { put(FieldKeys.IBAN, it) }
        }
    }

    private fun correspondent(known: Collection<String>): String? {
        val seller = sellerName ?: return null
        PartyNames.matching(seller, known)?.let { return it }
        return seller.takeIf { buyerName == null || PartyNames.matching(buyerName, known) == null }
    }

    private fun negative(amount: String): String = if (amount.startsWith("-") || amount == "0") amount else "-$amount"
}

/**
 * Party names compared as a person reads them: in any case, spacing and punctuation, with a trailing
 * legal form left off, so "ACME Supplies B.V." is the library's "Acme Supplies".
 */
internal object PartyNames {
    private val legalForms =
        setOf(
            "ab",
            "ag",
            "as",
            "bv",
            "bvba",
            "co",
            "gmbh",
            "inc",
            "kg",
            "limited",
            "llc",
            "ltd",
            "nv",
            "oy",
            "plc",
            "sa",
            "sarl",
            "sas",
            "sl",
            "spa",
            "srl",
            "ug",
            "vof",
        )

    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** The one of [known] that names the same party as [name], or null. */
    fun matching(
        name: String,
        known: Collection<String>,
    ): String? {
        val wanted = key(name).ifEmpty { return null }
        return known.firstOrNull { key(it) == wanted }
    }

    private fun key(name: String): String {
        val words =
            name
                .lowercase()
                .replace(".", "")
                .split(separators)
                .filter { it.isNotEmpty() }
                .toMutableList()
        while (words.size > 1 && words.last() in legalForms) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }
}

/**
 * Finds the e-invoice among a PDF's embedded XML files ([PdfEngine.firstEmbeddedXml], which reads a
 * known Factur-X/ZUGFeRD/XRechnung attachment first): the first one whose root is a CII or UBL
 * invoice with at least one value wins. Each attachment is parsed as it is read, so only one is held
 * at a time.
 */
object EInvoiceDetector {
    suspend fun detect(
        engine: PdfEngine,
        pdf: ByteArray,
    ): EInvoiceData? = engine.firstEmbeddedXml(pdf) { parseEInvoiceXml(it.bytes) }
}

/**
 * Read [xml] as a CII (`rsm:CrossIndustryInvoice`) or UBL (`Invoice` or `CreditNote`) e-invoice, or
 * null when it is neither, is malformed, or tries to pull in a DTD or an external entity. The parser
 * never resolves external resources and never expands a DOCTYPE.
 */
expect fun parseEInvoiceXml(xml: ByteArray): EInvoiceData?
