package dev.npn.core.util

import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** The Android-target log file must use only java.nio APIs that minSdk 30 actually has. */
class RotatingLogFileAndroidTest {
    private val directory = Files.createTempDirectory("npn-android-log")

    @AfterTest
    fun cleanup() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun appendsAndTailsWithoutWriteString() {
        val file = RotatingLogFile(directory.resolve("npn.log"), maxBytes = 40)
        file.append("first\n")
        file.append("second\n")
        file.append("third\n")

        file.tail(4_000) shouldContain "third"
    }
}
