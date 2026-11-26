package dev.npn.core.pdf

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * One XML attachment a PDF lists, before anything of it is read: its [name], its PDF/A-3
 * `AFRelationship` ([relationship], null when it has none), and how to read it within a budget
 * ([open], null when it is too large or its encoding is not one this reads).
 */
internal class EmbeddedXmlCandidate(
    val name: String,
    val relationship: String?,
    val open: (EmbeddedXmlBudget) -> ByteArray?,
)

/**
 * What is left of one PDF's [MAX_EMBEDDED_XML_TOTAL_BYTES]. Every byte read is charged, the bytes of
 * an attachment that turned out too large included: reading them cost the same.
 */
internal class EmbeddedXmlBudget(
    private var remaining: Int = MAX_EMBEDDED_XML_TOTAL_BYTES,
) {
    val exhausted: Boolean get() = remaining <= 0

    /**
     * Read [stream] whole, or null when it holds more than [MAX_EMBEDDED_XML_BYTES] or than what is
     * left of the budget. A declared size can lie or be absent, so the cap is enforced while reading
     * rather than from a stream's metadata.
     */
    fun read(stream: InputStream): ByteArray? {
        val limit = minOf(MAX_EMBEDDED_XML_BYTES, remaining)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        try {
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) return out.toByteArray()
                total += read
                if (total > limit) return null
                out.write(buffer, 0, read)
            }
        } finally {
            remaining -= total
        }
    }
}

/**
 * The attachment names Factur-X, ZUGFeRD and XRechnung give their invoice, lower case: an attachment
 * so named is read before any other.
 */
private val E_INVOICE_NAMES = setOf("factur-x.xml", "zugferd-invoice.xml", "xrechnung.xml")

/** `AFRelationship` values that mark an attachment as the document's own data (PDF/A-3, Factur-X). */
private val DATA_RELATIONSHIPS = setOf("Alternative", "Data", "Source")

/**
 * [PdfEngine.firstEmbeddedXml] over the [candidates] one PDF lists: the likeliest e-invoice first,
 * each read only when its turn comes, at most [MAX_EMBEDDED_XML_FILES] read and all of them within one
 * [EmbeddedXmlBudget]. Every attempt counts against the files, one that turned out unreadable
 * included.
 */
internal fun <T : Any> readFirstEmbeddedXml(
    candidates: List<EmbeddedXmlCandidate>,
    read: (EmbeddedXmlFile) -> T?,
): T? {
    val budget = EmbeddedXmlBudget()
    // sortedBy is stable: within a rank, the document's order.
    for (candidate in candidates.sortedBy { it.rank() }.take(MAX_EMBEDDED_XML_FILES)) {
        if (budget.exhausted) return null
        val bytes = candidate.open(budget) ?: continue
        read(EmbeddedXmlFile(candidate.name, bytes))?.let { return it }
    }
    return null
}

private fun EmbeddedXmlCandidate.rank(): Int =
    when {
        name.lowercase() in E_INVOICE_NAMES -> 0
        relationship in DATA_RELATIONSHIPS -> 1
        else -> 2
    }

/**
 * Most file specifications of a PDF's embedded-files name tree looked at. Listing one reads nothing
 * of its file, but a tree with many thousands is not an e-invoice's.
 */
internal const val MAX_EMBEDDED_FILE_ENTRIES = 1024
