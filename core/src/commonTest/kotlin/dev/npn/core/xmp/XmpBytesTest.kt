package dev.npn.core.xmp

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class XmpBytesTest {
    private val packet = "<?xpacket?><npn:documentId>abc</npn:documentId>"

    @Test
    fun decodesPlainUtf8() {
        decodeXmpBytes(packet.encodeToByteArray()) shouldBe packet
    }

    @Test
    fun stripsUtf8Bom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + packet.encodeToByteArray()
        decodeXmpBytes(bytes) shouldBe packet
    }

    @Test
    fun stripsEncodedLeadingFeffWithoutByteBom() {
        // A source that carried U+FEFF as data (no byte-level BOM) still parses cleanly.
        val bytes = "\uFEFF$packet".encodeToByteArray()
        decodeXmpBytes(bytes) shouldBe packet
    }

    @Test
    fun decodesUtf16BeWithBom() {
        val bytes = utf16Be(packet, withBom = true)
        decodeXmpBytes(bytes) shouldBe packet
    }

    @Test
    fun decodesUtf16LeWithBom() {
        val bytes = utf16Le(packet, withBom = true)
        decodeXmpBytes(bytes) shouldBe packet
    }

    @Test
    fun utf16BeRoundTripsThroughParser() {
        val xml = "<x><dc:subject><rdf:Bag><rdf:li>invoice</rdf:li></rdf:Bag></dc:subject></x>"
        val decoded = decodeXmpBytes(utf16Be(xml, withBom = true))
        XmpParser.parse(decoded).tags shouldBe listOf("invoice")
    }

    private fun utf16Be(
        text: String,
        withBom: Boolean,
    ): ByteArray {
        val body = ByteArray(text.length * 2)
        for ((i, ch) in text.withIndex()) {
            val code = ch.code
            body[i * 2] = ((code shr 8) and 0xFF).toByte()
            body[i * 2 + 1] = (code and 0xFF).toByte()
        }
        return if (withBom) byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + body else body
    }

    private fun utf16Le(
        text: String,
        withBom: Boolean,
    ): ByteArray {
        val body = ByteArray(text.length * 2)
        for ((i, ch) in text.withIndex()) {
            val code = ch.code
            body[i * 2] = (code and 0xFF).toByte()
            body[i * 2 + 1] = ((code shr 8) and 0xFF).toByte()
        }
        return if (withBom) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body else body
    }
}
