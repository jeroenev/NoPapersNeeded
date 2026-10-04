package dev.npn.core.index

import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The app's temporary files in the library: a rescan never takes one for a document, and sweeps
 * away the ones a crash left behind once they are old, but never one that may hold the only copy
 * of a document.
 */
class TempFileSweepTest {
    private val rig = IndexRig.create()

    @AfterTest
    fun teardown() = rig.close()

    private val now = System.currentTimeMillis()

    /** Write [relPath] with an mtime [ageMillis] in the past. */
    private fun file(
        relPath: String,
        ageMillis: Long = STALE,
        content: String = "bytes of $relPath",
    ): String {
        rig.writePdf(relPath, content)
        Files.setLastModifiedTime(rig.libRoot.resolve(relPath), FileTime.fromMillis(now - ageMillis))
        return relPath
    }

    private suspend fun documentPaths(): List<String> =
        rig.db
            .documentDao()
            .allPathsAndMeta()
            .map { it.relPath }

    @Test
    fun aRescanSweepsTheTemporaryFilesACrashLeftBehind() =
        runTest {
            rig.clock.now = now
            file("docs/a.pdf")
            val stale =
                listOf(
                    file("inbox/.npn-tmp-123456.part"),
                    file("inbox/" + NpnPaths.Temp.moveName(NONCE)),
                    file(NpnPaths.Temp.relocationPath("archive/b.pdf", NONCE)),
                    file(NpnPaths.Temp.bakePath("docs/a.pdf")),
                    file(NpnPaths.Temp.importPath(NONCE)),
                    file("${NpnPaths.TMP_DIR}/.npn-tmp-42.part"),
                    file("${NpnPaths.OCR_DIR}/.npn-tmp-7.part"),
                    file("${NpnPaths.DEVICES_DIR}/device/" + NpnPaths.Temp.markerStagingName("install", NONCE)),
                )

            rig.coordinator().rescan()

            stale.filter(rig::exists).shouldBeEmpty()
            documentPaths() shouldContainExactlyInAnyOrder listOf("docs/a.pdf")
        }

    @Test
    fun aRescanKeepsFreshTemporaryFilesAndEveryPossibleOnlyCopy() =
        runTest {
            rig.clock.now = now
            val fresh =
                listOf(
                    file("inbox/.npn-tmp-123456.part", ageMillis = HOUR),
                    file(NpnPaths.Temp.importPath(NONCE), ageMillis = HOUR),
                    file("inbox/" + NpnPaths.Temp.moveName(NONCE), ageMillis = HOUR),
                )
            // A move interrupted mid-way: the document's only copy, under its recovery name.
            val recovery = file("docs/" + NpnPaths.Temp.recoveryName(NONCE))
            // A bake interrupted after the original was deleted: the baked document's only copy.
            val orphanBake = file(NpnPaths.Temp.bakePath("docs/gone.pdf"))
            // A user's own file that only looks like one of ours.
            val lookalike = file("notes/npn-tmp.part")

            rig.coordinator().rescan()

            (fresh + recovery + orphanBake + lookalike).all(rig::exists).shouldBeTrue()
            // The recovery file is the document, so the rescan indexes it like any PDF.
            documentPaths() shouldContainExactlyInAnyOrder listOf(recovery)
        }

    @Test
    fun aRescanTakesNoTemporaryFileForADocument() =
        runTest {
            rig.clock.now = now
            file("docs/a.pdf")
            file("docs/.npn-tmp-1.pdf.part")
            file(NpnPaths.Temp.relocationPath("docs/b.pdf", NONCE))
            file(NpnPaths.Temp.bakePath("docs/a.pdf"), ageMillis = HOUR)
            file("docs/" + NpnPaths.Temp.moveName(NONCE), ageMillis = HOUR)

            rig.coordinator().rescan()

            documentPaths() shouldContainExactlyInAnyOrder listOf("docs/a.pdf")
        }

    @Test
    fun aWriteStagingFileLeftInAJournalBucketDoesNotBlockWrites() =
        runTest {
            file("a.pdf")
            val coordinator = rig.coordinator()
            coordinator.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .allPathsAndMeta()
                    .single()
                    .docId
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "first"))).shouldBeTrue()
            // A process killed while writing a journal object's staging file leaves the direct
            // filesystem's own temporary file in the bucket, which every replica then syncs.
            val bucket =
                rig.fs
                    .listAllUnder(NpnPaths.JOURNAL_DIR)
                    .first { it.isDirectory }
                    .relPath
            file("$bucket/.npn-tmp-987654.part", ageMillis = HOUR)

            coordinator.reconcileMetadata()

            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "second"))).shouldBeTrue()
            rig.db
                .tagDao()
                .tagsForDocument(docId)
                .contains("second")
                .shouldBeTrue()
        }

    private companion object {
        const val HOUR = 60L * 60L * 1000L
        const val STALE = 48L * HOUR
        const val NONCE = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
