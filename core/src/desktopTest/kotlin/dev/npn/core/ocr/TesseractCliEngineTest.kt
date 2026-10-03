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
    fun languagesWithoutAnInstalledModelAreSkipped() =
        runTest {
            if (System.getProperty("os.name").startsWith("Windows")) return@runTest
            val dir = Files.createTempDirectory("npn-tess")
            // Has English and Dutch; otherwise echoes the `-l` value it was run with
            // (`tesseract <input> stdout -l <lang>`).
            val fake =
                dir.resolve("tesseract").toFile().apply {
                    writeText(
                        """
                        #!/bin/sh
                        case "${'$'}1" in
                          --version) exit 0 ;;
                          --list-langs) printf 'List of available languages in "/x/" (2):\neng\nnld\n' ;;
                          *) printf '%s' "${'$'}4" ;;
                        esac
                        """.trimIndent() + "\n",
                    )
                    setExecutable(true)
                }
            try {
                val engine = TesseractCliEngine(binary = fake.absolutePath)
                engine.recognizePng(byteArrayOf(), "deu+nld+eng") shouldBe "nld+eng"
                // Nothing requested is installed: pass it through and let Tesseract report it.
                engine.recognizePng(byteArrayOf(), "ita") shouldBe "ita"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun aModelInstalledWhileRunningIsPickedUp() =
        runTest {
            if (System.getProperty("os.name").startsWith("Windows")) return@runTest
            val dir = Files.createTempDirectory("npn-tess")
            // `--list-langs` reads the installed models from a file the test changes mid-run.
            val models = dir.resolve("models").toFile().apply { writeText("eng\n") }
            val fake =
                dir.resolve("tesseract").toFile().apply {
                    writeText(
                        """
                        #!/bin/sh
                        case "${'$'}1" in
                          --version) exit 0 ;;
                          --list-langs) printf 'List of available languages:\n'; cat "${models.absolutePath}" ;;
                          *) printf '%s' "${'$'}4" ;;
                        esac
                        """.trimIndent() + "\n",
                    )
                    setExecutable(true)
                }
            try {
                val engine = TesseractCliEngine(binary = fake.absolutePath)
                engine.languagesFor("ita+eng") shouldBe "eng"
                models.writeText("eng\nita\n")
                engine.languagesFor("ita+eng") shouldBe "ita+eng"
                engine.recognizePng(byteArrayOf(), "ita+eng") shouldBe "ita+eng"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun nameIsTesseract() {
        TesseractCliEngine().name shouldBe "tesseract"
    }
}
