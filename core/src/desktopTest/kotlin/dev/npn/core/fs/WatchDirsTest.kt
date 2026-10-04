package dev.npn.core.fs

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** [watchDirs] over a real [java.nio.file.WatchService], so each test waits on real time, bounded. */
class WatchDirsTest {
    private lateinit var root: Path

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("npn-watch-test")
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aRecursiveWatchAlsoWatchesADirectoryCreatedLater() =
        watching(watchDirs(root, recursive = true)) { changes ->
            val sub = Files.createDirectory(root.resolve("sub"))
            changes.receiveUntil(sub)
            // The directory is watched before its own creation is reported.
            val inside = Files.write(sub.resolve("a.pdf"), byteArrayOf(1))
            changes.receiveUntil(inside)
        }

    @Test
    fun aSingleDirectoryWatchLeavesItsSubdirectoriesAlone() =
        watching(watchDirs(root, recursive = false, kinds = arrayOf(ENTRY_CREATE, ENTRY_MODIFY))) { changes ->
            val sub = Files.createDirectory(root.resolve("sub"))
            changes.receiveUntil(sub)
            Files.write(sub.resolve("nested.pdf"), byteArrayOf(1))
            val last = Files.write(root.resolve("last.pdf"), byteArrayOf(1))

            changes.receiveUntil(last).filterNotNull().filter { it.parent == sub } shouldBe emptyList()
        }

    @Test
    fun aMissingDirectoryGoesQuietInsteadOfFailing() {
        val first = runBlocking { withTimeoutOrNull(QUIET_MILLIS) { watchDirs(root.resolve("absent"), recursive = true).first() } }
        first shouldBe null
    }

    /**
     * Collect [flow] while [block] runs, once the watch is registered: probe files are written into
     * [root] until one is reported.
     */
    private fun watching(
        flow: Flow<Path?>,
        block: suspend CoroutineScope.(ReceiveChannel<Path?>) -> Unit,
    ): Unit = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val changes = Channel<Path?>(Channel.UNLIMITED)
            val collector = launch { flow.collect { changes.send(it) } }
            try {
                var probe = 0
                while (withTimeoutOrNull(PROBE_MILLIS) { changes.receive() } == null) {
                    Files.write(root.resolve("probe-${probe++}"), byteArrayOf(1))
                }
                block(changes)
            } finally {
                collector.cancel()
            }
        }
    }

    /** Receive until [path] is reported; returns everything received before it. */
    private suspend fun ReceiveChannel<Path?>.receiveUntil(path: Path): List<Path?> {
        val before = mutableListOf<Path?>()
        var next = receive()
        while (next != path) {
            before += next
            next = receive()
        }
        return before
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val PROBE_MILLIS = 100L
        const val QUIET_MILLIS = 300L
    }
}
