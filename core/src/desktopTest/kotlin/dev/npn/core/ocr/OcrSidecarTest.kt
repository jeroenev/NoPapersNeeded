package dev.npn.core.ocr

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class OcrSidecarTest {

    @Test
    fun encodesExactWireFormat() {
        val sidecar = OcrSidecar(
            v = 1,
            doc = "a".repeat(64),
            dev = "device-1",
            engine = "embedded",
            lang = "eng",
            createdAt = 1720000000000L,
            pages = listOf("page 1 text", "..."),
        )
        val json = OcrSidecarCodec.encode(sidecar)
        // Exact field order and shape per PLAN.md "OCR sidecars".
        json shouldBe
            "{\"v\":1,\"doc\":\"${"a".repeat(64)}\",\"dev\":\"device-1\"," +
            "\"engine\":\"embedded\",\"lang\":\"eng\",\"createdAt\":1720000000000," +
            "\"pages\":[\"page 1 text\",\"...\"]}"
    }

    @Test
    fun roundtripsThroughCodec() {
        val sidecar = OcrSidecar(
            doc = "b".repeat(64),
            dev = "dev-x",
            engine = "mixed",
            lang = "nld",
            createdAt = 42L,
            pages = listOf("een", "twee", ""),
        )
        val decoded = OcrSidecarCodec.decode(OcrSidecarCodec.encode(sidecar))
        decoded shouldBe sidecar
    }

    @Test
    fun fullTextJoinsPagesWithBlankLine() {
        val sidecar = OcrSidecar(
            doc = "c".repeat(64),
            dev = "d",
            engine = "tesseract",
            lang = "eng",
            createdAt = 0L,
            pages = listOf("first", "second"),
        )
        sidecar.fullText() shouldBe "first\n\nsecond"
    }

    @Test
    fun toleratesUnknownKeys() {
        val json = "{\"v\":1,\"doc\":\"${"d".repeat(64)}\",\"dev\":\"x\",\"engine\":\"mlkit\"," +
            "\"lang\":\"eng\",\"createdAt\":1,\"pages\":[\"p\"],\"extra\":\"ignored\"}"
        OcrSidecarCodec.decode(json).engine shouldBe "mlkit"
    }
}
