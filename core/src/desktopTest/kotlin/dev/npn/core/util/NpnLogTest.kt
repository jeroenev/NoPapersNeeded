package dev.npn.core.util

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class NpnLogTest {
    @Test
    fun runLoggedReportsAFailureAndReturnsNull() {
        val log = RecordingLog()
        val failure = IllegalStateException("disk full")

        log.runLogged("rescan") { throw failure } shouldBe null

        log.entries shouldBe listOf(RecordingLog.Entry(NpnLog.Level.WARN, "rescan failed", failure))
    }

    @Test
    fun runLoggedPassesASuccessThroughWithoutLogging() {
        val log = RecordingLog()
        log.runLogged("rescan") { 7 } shouldBe 7
        log.entries.shouldBeEmpty()
    }

    @Test
    fun runLoggedRethrowsCancellationWithoutLoggingIt() {
        val log = RecordingLog()
        assertFailsWith<CancellationException> { log.runLogged("rescan") { throw CancellationException("closing") } }
        log.entries.shouldBeEmpty()
    }

    @Test
    fun jvmLogWritesEachEntryToStderrAndTheFile() {
        val dir = Files.createTempDirectory("npn-log")
        try {
            val file = dir.resolve("logs/npn.log")
            val stderr = ByteArrayOutputStream()
            val log = JvmLog(file, stderr = PrintStream(stderr, true))

            log.warn("rescan failed", IllegalStateException("disk full"))

            val written = Files.readString(file)
            written shouldContain "WARN rescan failed"
            written shouldContain "IllegalStateException: disk full"
            stderr.toString() shouldBe written
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmLogRollsTheFileOverOnceItReachesTheCap() {
        val dir = Files.createTempDirectory("npn-log")
        try {
            val file = dir.resolve("npn.log")
            val log = JvmLog(file, maxBytes = 200, stderr = PrintStream(ByteArrayOutputStream()))

            repeat(10) { log.info("entry $it ${"x".repeat(40)}") }

            val current = Files.readString(file)
            val previous = Files.readString(dir.resolve("npn.log.1"))
            current shouldContain "entry 9"
            previous shouldNotContain "entry 9"
            // Never more than one entry past the cap, and no third file.
            (Files.size(file) < 300) shouldBe true
            Files.list(dir).use { it.count() } shouldBe 2L
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmLogSurvivesAFileItCannotWrite() {
        val dir = Files.createTempDirectory("npn-log")
        try {
            // A directory where the file should be: every write fails.
            val file = Files.createDirectories(dir.resolve("npn.log").resolve("blocked")).parent
            val stderr = ByteArrayOutputStream()

            JvmLog(file, stderr = PrintStream(stderr, true)).error("still reported")

            stderr.toString() shouldContain "ERROR still reported"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
