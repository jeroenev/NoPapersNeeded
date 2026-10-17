package dev.npn.core.index

import dev.npn.core.db.documentEntity
import dev.npn.core.fs.FileMeta
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class RelinkPairingTest {
    private fun missing(
        id: String,
        relPath: String,
        size: Long,
    ) = documentEntity(id, relPath, size = size, mtime = 1, addedAt = 1, missing = true)

    private fun found(
        relPath: String,
        size: Long,
    ) = FileMeta(relPath, relPath.substringAfterLast('/'), size, 1, false)

    /**
     * A batch tool rewriting a folder (ocrmypdf over a thousand scans into an output folder) makes a
     * thousand documents go missing and a thousand new ones appear. Every name is within a couple
     * of edits of hundreds of others; the candidates must stay a handful per document, its own
     * file first, rather than hundreds of thousands of rows for the Trash screen.
     */
    @Test
    fun aThousandByAThousandKeepsAFewCandidatesPerDocument() {
        val count = 1_000
        val missingDocs = (0 until count).map { missing("old-$it", "scans/scan-${pad(it)}.pdf", size = 100L + it) }
        val newDocs = (0 until count).associate { "new-$it" to found("ocr/scan-${pad(it)}.pdf", 5_000L + it) }

        lateinit var candidates: List<RelinkCandidate>
        val elapsed = measureTime { candidates = RelinkPairing.pair(missingDocs, newDocs, existing = emptyList()) }

        val perDoc = candidates.groupBy { it.oldDocId }
        perDoc.keys.size shouldBe count
        perDoc.values.maxOf { it.size } shouldBeLessThanOrEqual RelinkPairing.MAX_CANDIDATES_PER_DOC
        perDoc.all { (old, ranked) -> ranked.first().newDocId == old.replace("old-", "new-") } shouldBe true
        // Generous: the work per pair is bounded. The duplicate checks this replaces scanned the
        // growing result for every pair and did not finish at this size.
        (elapsed < 30.seconds) shouldBe true
    }

    @Test
    fun theBestCandidatesWinAndTiesAreStable() {
        val gone = missing("old", "a/invoice.pdf", size = 10)
        val candidates =
            RelinkPairing.pair(
                listOf(gone),
                mapOf(
                    "same-name-elsewhere" to found("b/invoice.pdf", 99),
                    "same-name-and-size" to found("c/INVOICE.pdf", 10),
                    "similar-name-same-dir" to found("a/invoice_ocr.pdf", 77),
                    "same-size-only" to found("d/receipt.pdf", 10),
                    "another-similar" to found("e/invoices.pdf", 55),
                ),
                existing = emptyList(),
            )

        candidates.map { it.newDocId to it.signals } shouldContainExactly
            listOf(
                "same-name-and-size" to setOf(RelinkSignal.SAME_NAME, RelinkSignal.SAME_SIZE),
                "same-name-elsewhere" to setOf(RelinkSignal.SAME_NAME),
                "same-size-only" to setOf(RelinkSignal.SAME_SIZE),
            )
    }

    @Test
    fun sharingOnlyADirectoryIsNoCandidate() {
        val candidates =
            RelinkPairing.pair(
                listOf(missing("old", "inbox/electricity.pdf", size = 10)),
                mapOf("new" to found("inbox/passport.pdf", 99)),
                existing = emptyList(),
            )

        candidates.shouldBeEmpty()
    }

    @Test
    fun aPairAlreadyProposedIsNotRepeated() {
        val gone = missing("old", "a/invoice.pdf", size = 10)
        val pathMatch = RelinkCandidate("old", "new", setOf(RelinkSignal.REPLACED_IN_PLACE), score = 100)

        RelinkPairing
            .pair(listOf(gone), mapOf("new" to found("a/invoice.pdf", 10)), existing = listOf(pathMatch))
            .shouldBeEmpty()
    }

    @Test
    fun aRescanProposesTheFileThatTookAMissingDocumentsName() =
        indexTest { rig ->
            rig.writePdf("2024/invoice.pdf", "alpha")
            rig.writePdf("2024/passport.pdf", "papers")
            rig.rescan()
            val oldId = checkNotNull(rig.db.documentDao().rowAtPath("2024/invoice.pdf")).docId

            rig.deleteFile("2024/invoice.pdf")
            rig.writePdf("archive/invoice.pdf", "alpha, edited")
            rig.writePdf("2024/unrelated.pdf", "something else entirely")
            rig.clock.advance()
            val report = rig.rescan()

            val newId = checkNotNull(rig.db.documentDao().rowAtPath("archive/invoice.pdf")).docId
            report.relinkCandidates.map { Triple(it.oldDocId, it.newDocId, it.signals) } shouldContainExactly
                listOf(Triple(oldId, newId, setOf(RelinkSignal.SAME_NAME)))
        }

    private fun pad(index: Int) = index.toString().padStart(4, '0')
}
