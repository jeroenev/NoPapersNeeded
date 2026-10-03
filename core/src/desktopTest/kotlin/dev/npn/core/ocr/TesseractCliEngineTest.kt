package dev.npn.core.ocr

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
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
    fun bundledTessdataDirReachesTheProcess() =
        runTest {
            // A shell script stands in for the binary, so this needs a POSIX shell.
            if (System.getProperty("os.name").startsWith("Windows")) return@runTest
            val dir = Files.createTempDirectory("npn-tess")
            val fake =
                dir.resolve("tesseract").toFile().apply {
                    writeText("#!/bin/sh\nprintf '%s' \"\$TESSDATA_PREFIX\"\n")
                    setExecutable(true)
                }
            try {
                val engine = TesseractCliEngine(binary = fake.absolutePath, tessdataDir = "/bundled/tessdata")
                engine.available().shouldBeTrue()
                engine.recognizePng("png".encodeToByteArray(), "eng") shouldBe "/bundled/tessdata"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun nameIsTesseract() {
        TesseractCliEngine().name shouldBe "tesseract"
    }
}
