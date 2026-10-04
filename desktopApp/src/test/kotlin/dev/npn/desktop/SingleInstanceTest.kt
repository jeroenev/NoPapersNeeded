package dev.npn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingleInstanceTest {
    @Test
    fun aSecondProcessFindsTheLockTaken() {
        val lockFile = Files.createTempDirectory("npn-instance").resolve("instance.lock")
        // This test JVM plays the running app; a lock is per process, so the second one must be
        // a process of its own.
        assertTrue(SingleInstance.claim(lockFile) {})

        val second =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                SecondInstance::class.java.name,
                lockFile.toString(),
            ).redirectErrorStream(true).start()
        assertTrue(second.waitFor(60, TimeUnit.SECONDS), "second instance did not finish")

        assertEquals(
            "taken",
            second.inputStream
                .bufferedReader()
                .readText()
                .trim(),
        )
    }
}

/** Entry point of the second process in [SingleInstanceTest]. */
object SecondInstance {
    @JvmStatic
    fun main(args: Array<String>) {
        println(if (SingleInstance.claim(Path.of(args.single())) {}) "claimed" else "taken")
    }
}
