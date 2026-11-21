package dev.npn.core.ocr

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class TesseractCliEngineTest {
    @Test
    fun detectionDoesNotThrowWhenBinaryAbsent() =
        runTest {
            // Point at a binary that certainly does not exist: detect() must swallow the
            // IOException and report unavailable rather than throwing.
            val engine = TesseractCliEngine(binary = "tesseract-does-not-exist-xyz")
            engine.available().shouldBeFalse()
            // Asked to read a page anyway, it fails rather than reporting a blank page.
            shouldThrow<IOException> { engine.recognizePng("png".encodeToByteArray(), "eng") }
        }

    @Test
    fun aHungRunTimesOutAndItsProcessIsKilled() =
        runTest {
            assumeTrue(isLinux())
            val dir = Files.createTempDirectory("npn-tess")
            val pidFile = dir.resolve("pid").toFile()
            // `exec`, so the pid written is the sleeping process itself.
            val fake = fakeTesseract(dir, "echo $$ > '${pidFile.absolutePath}'; exec sleep 30")
            try {
                val engine = TesseractCliEngine(binary = fake.absolutePath, timeoutSeconds = 1)

                val error = shouldThrow<IOException> { engine.recognizePng(byteArrayOf(), "eng") }

                error.message.orEmpty() shouldContain "timed out"
                isAlive(pidFile).shouldBeFalse()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun aCancelledRunKillsItsProcess() =
        runTest {
            assumeTrue(isLinux())
            val dir = Files.createTempDirectory("npn-tess")
            val pidFile = dir.resolve("pid").toFile()
            val fake = fakeTesseract(dir, "echo $$ > '${pidFile.absolutePath}'; exec sleep 30")
            try {
                val engine = TesseractCliEngine(binary = fake.absolutePath, timeoutSeconds = 30)
                val run = launch { engine.recognizePng(byteArrayOf(), "eng") }
                testScheduler.runCurrent()
                awaitStarted(pidFile)

                val cancelledAt = System.nanoTime()
                run.cancelAndJoin()

                // A run that ignored cancellation would hold the join for the script's whole sleep.
                (System.nanoTime() - cancelledAt) shouldBeLessThan CANCEL_BOUND_NANOS
                isAlive(pidFile).shouldBeFalse()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun aNonZeroExitThrowsWithTheProcessError() =
        runTest {
            assumeTrue(isLinux())
            val dir = Files.createTempDirectory("npn-tess")
            val fake = fakeTesseract(dir, "echo 'Error opening data file' >&2; exit 1")
            try {
                val engine = TesseractCliEngine(binary = fake.absolutePath)

                val error = shouldThrow<IOException> { engine.recognizePng(byteArrayOf(), "eng") }

                error.message.orEmpty() shouldContain "Error opening data file"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun theRenderedPageIsReadableOnlyByItsOwner() =
        runTest {
            assumeTrue(isLinux())
            val dir = Files.createTempDirectory("npn-tess")
            // The page is handed over as a file in the shared temp directory; report its mode.
            val fake = fakeTesseract(dir, "stat -c %a \"${'$'}1\"")
            try {
                val engine = TesseractCliEngine(binary = fake.absolutePath)

                engine.recognizePng("png".encodeToByteArray(), "eng") shouldBe "600"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    /** A `tesseract` that answers the probes and runs [onRecognize] for a page. */
    private fun fakeTesseract(
        dir: Path,
        onRecognize: String,
    ): File =
        dir.resolve("tesseract").toFile().apply {
            writeText(
                """
                #!/bin/sh
                case "${'$'}1" in
                  --version) exit 0 ;;
                  --list-langs) printf 'List of available languages:\neng\n' ;;
                  *) $onRecognize ;;
                esac
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }

    @Test
    fun bundledTessdataDirReachesTheProcess() =
        runTest {
            // A shell script stands in for the binary, so this needs a POSIX shell.
            assumeTrue(hasPosixShell())
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
            assumeTrue(hasPosixShell())
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
            assumeTrue(hasPosixShell())
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

    /** First run's language step asks which models are there, and learns when there is no Tesseract. */
    @Test
    fun theInstalledModelsAreListedAndNoneWithoutTesseract() =
        runTest {
            assumeTrue(hasPosixShell())
            TesseractCliEngine(binary = "tesseract-does-not-exist-xyz").installedLanguages() shouldBe null
            val dir = Files.createTempDirectory("npn-tess")
            try {
                val engine = TesseractCliEngine(binary = fakeTesseract(dir, "exit 0").absolutePath)
                engine.installedLanguages() shouldBe setOf("eng")
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun nameIsTesseract() {
        TesseractCliEngine().name shouldBe "tesseract"
    }

    private fun isLinux() = System.getProperty("os.name").startsWith("Linux")

    /** The fakes are shell scripts, which Windows cannot run as a binary. */
    private fun hasPosixShell() = !System.getProperty("os.name").startsWith("Windows")

    /** Blocks until the fake has written its pid: the process is up and sleeping. */
    private fun awaitStarted(pidFile: File) {
        repeat(POLL_ATTEMPTS) {
            if (pidFile.exists() && pidFile.readText().isNotBlank()) return
            Thread.sleep(POLL_MS)
        }
        error("the fake tesseract never started")
    }

    private fun isAlive(pidFile: File): Boolean {
        val pid = pidFile.readText().trim().toLong()
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    private companion object {
        const val POLL_MS = 20L
        const val POLL_ATTEMPTS = 250
        const val CANCEL_BOUND_NANOS = 5_000_000_000L
    }
}
