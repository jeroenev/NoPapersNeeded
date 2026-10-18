package dev.npn.core.app

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.Collections
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.test.Test

/**
 * The session's periodic tick only reads the journal. Builds before this one compacted it there:
 * they folded the objects into a snapshot and deleted them, once enough had settled.
 */
class JournalTickTest {
    /** Once [recording], records each listing of the journal, and every delete and write under it. */
    private class JournalRecorder(
        private val fs: LibraryFileSystem,
    ) : LibraryFileSystem by fs {
        @Volatile var recording = false
        val listings = Channel<Unit>(Channel.UNLIMITED)
        val changes: MutableList<String> = Collections.synchronizedList(ArrayList())

        override suspend fun listAllUnder(relPath: String): List<FileMeta> =
            fs.listAllUnder(relPath).also { if (recording && relPath == NpnPaths.JOURNAL_DIR) listings.trySend(Unit) }

        override suspend fun delete(relPath: String) {
            if (recording && relPath.startsWith(NpnPaths.JOURNAL_DIR)) changes += "delete $relPath"
            fs.delete(relPath)
        }

        override suspend fun writeBytesOnce(
            relPath: String,
            bytes: ByteArray,
        ) {
            if (recording && relPath.startsWith(NpnPaths.JOURNAL_DIR)) changes += "write $relPath"
            fs.writeBytesOnce(relPath, bytes)
        }
    }

    @Test
    fun aTickWritesNoSnapshotAndDeletesNothingUnderTheJournal() =
        runTest {
            var recorder: JournalRecorder? = null
            AppSessionRig
                .create(backgroundScope) { fs -> JournalRecorder(fs).also { recorder = it } }
                .use { rig ->
                    val journal = checkNotNull(recorder)
                    // More objects than any earlier build let pile up before it compacted them.
                    val other = rig.writer("device-two")
                    repeat(OBJECTS) { i ->
                        rig.clock.advance()
                        other.commit(listOf(PendingOp.settingSet("key-$i", "value")))
                    }
                    rig.session.start()
                    rig.session.awaitInitialRescan()
                    val before = journalFiles(rig)
                    // Long after every object settled, by any stability window.
                    rig.clock.now = FAR_FUTURE
                    journal.recording = true

                    // A tick reads the journal once; the second one's read starts after the first tick ended.
                    repeat(2) { journal.listings.receive() }

                    journal.changes.shouldBeEmpty()
                    journalFiles(rig) shouldContainExactlyInAnyOrder before
                    before.count { JournalPaths.classify(it) is JournalPath.ObjectV2 } shouldBeGreaterThanOrEqual
                        OBJECTS
                    before.filter { JournalPaths.classify(it) is JournalPath.SnapshotV2 }.shouldBeEmpty()
                }
        }

    /** The files under the library's journal, as paths relative to the library. */
    private fun journalFiles(rig: AppSessionRig): List<String> =
        Files.walk(rig.libRoot.resolve(NpnPaths.JOURNAL_DIR)).use { paths ->
            paths
                .filter { it.isRegularFile() }
                .map { it.relativeTo(rig.libRoot).invariantSeparatorsPathString }
                .toList()
        }

    private companion object {
        const val OBJECTS = 80

        /** Long after the files this test writes were last modified, in 2096. */
        const val FAR_FUTURE = 4_000_000_000_000L
    }
}
