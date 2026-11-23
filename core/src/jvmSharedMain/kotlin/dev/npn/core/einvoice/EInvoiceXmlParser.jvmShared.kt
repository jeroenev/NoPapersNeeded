package dev.npn.core.einvoice

import dev.npn.core.model.FieldLocale
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues
import dev.npn.core.pdf.MAX_EMBEDDED_XML_BYTES
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

actual fun parseEInvoiceXml(xml: ByteArray): EInvoiceData? {
    if (xml.isEmpty() || xml.size > MAX_EMBEDDED_XML_BYTES) return null
    // Factur-X and XRechnung are UTF-8. Reject NUL-marked wide encodings before the textual DOCTYPE
    // check below, so that check cannot be sidestepped by encoding the same document differently.
    if (xml.take(4).any { it == 0.toByte() }) return null
    val source = xml.decodeToString()
    if (source.contains("<!DOCTYPE", ignoreCase = true) || source.contains("<!ENTITY", ignoreCase = true)) {
        return null
    }
    val document =
        try {
            secureFactory()
                .newDocumentBuilder()
                .apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
                .parse(ByteArrayInputStream(xml))
        } catch (_: Exception) {
            return null
        }
    val root = document.documentElement ?: return null
    val namespace = root.namespaceURI.orEmpty()
    val data =
        when {
            root.localNameOrNodeName() == "CrossIndustryInvoice" && namespace.contains("CrossIndustryInvoice") -> {
                parseCii(root)
            }

            root.localNameOrNodeName() == "Invoice" && namespace.contains("Invoice-2") -> {
                parseUbl(root)
            }

            else -> {
                null
            }
        }
    return data?.takeIf { !it.isEmpty }
}

/**
 * A parser that reads a document and nothing else: no DTD, no external entity, no XInclude, no
 * external schema. Some features are not offered by every platform parser; the DOCTYPE/ENTITY
 * pre-check and the empty entity resolver are the guarantees that hold either way.
 */
private fun secureFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        configure { isXIncludeAware = false }
        configure { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        configure { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        configure { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        configure { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        configure { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        configure { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        configure { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
    }

/** Apply one parser setting, passing over the ones this platform's parser does not know. */
private inline fun configure(setting: () -> Unit) {
    try {
        setting()
    } catch (_: ParserConfigurationException) {
        // Not supported here; the resolver and the DOCTYPE pre-check still hold.
    } catch (_: IllegalArgumentException) {
        // An unsupported attribute value; as above.
    } catch (_: UnsupportedOperationException) {
        // An unsupported mode; as above.
    }
}

private fun parseCii(root: Element): EInvoiceData {
    val exchanged = root.child("ExchangedDocument")
    val transaction = root.child("SupplyChainTradeTransaction")
    val agreement = transaction?.child("ApplicableHeaderTradeAgreement")
    val settlement = transaction?.child("ApplicableHeaderTradeSettlement")
    val summation = settlement?.child("SpecifiedTradeSettlementHeaderMonetarySummation")
    val total = summation?.child("GrandTotalAmount") ?: summation?.child("DuePayableAmount")
    return EInvoiceData(
        format = EInvoiceFormat.CII,
        invoiceNumber = exchanged?.child("ID")?.textValue(),
        issueDate = dateValue(exchanged?.child("IssueDateTime")?.child("DateTimeString")?.textValue()),
        dueDate = dateValue(settlement?.descendant("DueDateDateTime")?.child("DateTimeString")?.textValue()),
        totalAmount = decimalValue(total?.textValue()),
        currency =
            currencyValue(settlement?.child("InvoiceCurrencyCode")?.textValue() ?: total?.getAttribute("currencyID")),
        sellerName = ciiPartyName(agreement?.child("SellerTradeParty")),
        buyerName = ciiPartyName(agreement?.child("BuyerTradeParty")),
        paymentReference = settlement?.child("PaymentReference")?.textValue(),
        iban = settlement?.descendant("PayeePartyCreditorFinancialAccount")?.child("IBANID")?.textValue(),
    )
}

private fun parseUbl(root: Element): EInvoiceData {
    val monetary = root.child("LegalMonetaryTotal")
    val total = monetary?.child("TaxInclusiveAmount") ?: monetary?.child("PayableAmount")
    val means = root.child("PaymentMeans")
    return EInvoiceData(
        format = EInvoiceFormat.UBL,
        invoiceNumber = root.child("ID")?.textValue(),
        issueDate = dateValue(root.child("IssueDate")?.textValue()),
        dueDate = dateValue((root.child("DueDate") ?: root.descendant("DueDate"))?.textValue()),
        totalAmount = decimalValue(total?.textValue()),
        currency =
            currencyValue(
                root.child("DocumentCurrencyCode")?.textValue() ?: total?.getAttribute("currencyID"),
            ),
        sellerName = ublPartyName(root.child("AccountingSupplierParty")),
        buyerName = ublPartyName(root.child("AccountingCustomerParty")),
        paymentReference =
            means?.descendant("PaymentID")?.textValue(),
        iban = means?.descendant("PayeeFinancialAccount")?.child("ID")?.textValue(),
    )
}

private fun ciiPartyName(party: Element?): String? =
    party?.child("Name")?.textValue()
        ?: party?.child("SpecifiedLegalOrganization")?.child("Name")?.textValue()
        ?: party?.child("TradingBusinessName")?.textValue()

private fun ublPartyName(container: Element?): String? {
    val party = container?.child("Party") ?: container
    return party?.child("PartyName")?.child("Name")?.textValue()
        ?: party?.child("PartyLegalEntity")?.child("RegistrationName")?.textValue()
        ?: party?.descendant("PartyName")?.child("Name")?.textValue()
}

private fun Element.localNameOrNodeName(): String = localName ?: nodeName.substringAfter(':')

/** The first direct child element named [local], whatever its namespace prefix. */
private fun Element.child(local: String): Element? {
    val children = childNodes
    for (index in 0 until children.length) {
        val element = children.item(index) as? Element ?: continue
        if (element.localNameOrNodeName() == local) return element
    }
    return null
}

/** The first descendant element named [local], whatever its namespace prefix. */
private fun Element.descendant(local: String): Element? {
    val nodes = getElementsByTagName("*")
    for (index in 0 until nodes.length) {
        val element = nodes.item(index) as? Element ?: continue
        if (element.localNameOrNodeName() == local) return element
    }
    return null
}

private fun Element.textValue(): String? = textContent?.trim()?.ifEmpty { null }

/** A CII date (format 102, 203 or 204, or an ISO date) in canonical `yyyy-MM-dd`. */
private fun dateValue(raw: String?): String? {
    val value = raw?.trim()?.ifEmpty { null } ?: return null
    val iso = value.take(10)
    if (FieldValues.isIsoDate(iso)) return iso
    val digits = value.take(8)
    if (digits.length != 8 || digits.any { !it.isDigit() }) return null
    val basic = "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
    return basic.takeIf { FieldValues.isIsoDate(it) }
}

private fun decimalValue(raw: String?): String? =
    (FieldValues.canonicalize(FieldType.DECIMAL, raw, FieldLocale.CANONICAL) as? FieldParse.Ok)?.canonical

private fun currencyValue(raw: String?): String? {
    val code = raw?.trim()?.uppercase()?.ifEmpty { null } ?: return null
    return code.takeIf { it.length == 3 && it.all { character -> character.isLetter() } }
}
