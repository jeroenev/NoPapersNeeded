package dev.npn.core.util

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

class RotatingLogFileTest {
    private val directory = Files.createTempDirectory("npn-rotating-log")

    @AfterTest
    fun cleanup() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun tailReturnsTheMostRecentEntries() {
        val file = directory.resolve("npn.log")
        val log = JvmLog(file, stderr = PrintStream(ByteArrayOutputStream()), clock = FixedClock(1_000))

        log.info("first entry")
        log.warn("second entry")

        val tail = log.tail()
        tail shouldContain "first entry"
        tail shouldContain "second entry"
    }

    @Test
    fun tailAlsoReadsThePreviousFileAfterRotation() {
        val file = directory.resolve("npn.log")
        val log = JvmLog(file, maxBytes = 40, stderr = PrintStream(ByteArrayOutputStream()), clock = FixedClock(1_000))

        repeat(3) { log.info("entry $it") }

        val tail = log.tail(4_000)
        // The current file holds the newest entry; the previous one still holds the earliest.
        tail shouldContain "entry 2"
        tail shouldContain "entry 0"
        Files.exists(directory.resolve("npn.log.1")) shouldBe true
    }

    @Test
    fun tailIsEmptyForAHostWithoutAFile() {
        JvmLog().tail() shouldBe ""
    }

    @Test
    fun aFileThatCannotBeReadDoesNotThrow() {
        val file = Files.createDirectories(directory.resolve("npn.log").resolve("blocked")).parent.resolve("npn.log")
        // A directory where the file should be: writing and reading both fail.
        Files.createDirectory(file)
        val log = JvmLog(file, stderr = PrintStream(ByteArrayOutputStream()), clock = FixedClock(1_000))

        log.error("still safe")
        log.tail() shouldBe ""
    }
}

/** A clock whose time never moves, so a log entry's shape is stable in tests. */
private class FixedClock(
    private val now: Long,
) : EpochClock {
    override fun nowMillis(): Long = now
}
