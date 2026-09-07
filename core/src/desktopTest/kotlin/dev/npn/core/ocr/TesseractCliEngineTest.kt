package dev.npn.core.ocr

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TesseractCliEngineTest {
    @Test
    fun detectionDoesNotThrowWhenBinaryAbsent() =
        runTest {
            // Point at a binary that certainly does not exist: detect() must swallow the
            // IOException and report unavailable rather than throwing.
            val engine = TesseractCliEngine(binary = "tesseract-does-not-exist-xyz")
            engine.available().shouldBeFalse()
            // recognizePng on an unavailable engine returns empty, never throws.
            engine.recognizePng("png".encodeToByteArray(), "eng") shouldBe ""
        }

    @Test
    fun nameIsTesseract() {
        TesseractCliEngine().name shouldBe "tesseract"
    }
}
