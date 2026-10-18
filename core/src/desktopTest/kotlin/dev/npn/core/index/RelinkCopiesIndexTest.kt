package dev.npn.core.index

import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * A relink writes copies of the old document's metadata onto the new one in its own transaction
 * ([JournalCoordinator.relinkAndRescan]): the new document's metadata is its own from then on.
 */
class RelinkCopiesIndexTest {
    /** After older->newer->current, the current document shows the title newer was given after its relink. */
    @Test
    fun aChainCarriesTheLatestValueToItsEnd() =
        runTest {
            withCurrent { rig, coordinator, current ->
                val older = "a".repeat(64)
                val newer = "b".repeat(64)
                coordinator.commitAndApply(listOf(PendingOp.fieldSet(older, FieldKeys.TITLE, "Old")))
                coordinator.relinkAndRescan(older, newer)
                coordinator.commitAndApply(listOf(PendingOp.fieldSet(newer, FieldKeys.TITLE, "New")))
                coordinator.relinkAndRescan(newer, current)

                rig.rescan()

                rig.db
                    .documentDao()
                    .getByDocId(current)
                    ?.title shouldBe "New"
            }
        }

    /** Unpinning the OCR engine of a relinked document unpins the pin it was given, for good. */
    @Test
    fun aClearOnTheTargetStands() =
        runTest {
            withCurrent { rig, coordinator, current ->
                val older = "a".repeat(64)
                coordinator.commitAndApply(listOf(PendingOp.propSet(older, SystemProperty.OCR_ENGINE, "tesseract")))
                coordinator.relinkAndRescan(older, current)
                rig.db
                    .documentDao()
                    .getByDocId(current)
                    ?.ocrEngineOverride shouldBe "tesseract"

                coordinator.commitAndApply(listOf(PendingOp.propSet(current, SystemProperty.OCR_ENGINE, null)))
                rig.rescan()

                rig.db
                    .documentDao()
                    .getByDocId(current)
                    ?.ocrEngineOverride shouldBe null
            }
        }

    /** A rig whose library holds one document, `c.pdf`, handed to [block] with a coordinator and its id. */
    private suspend fun withCurrent(block: suspend (IndexRig, JournalCoordinator, String) -> Unit) =
        IndexRig.create().use { rig ->
            rig.writePdf("c.pdf", "charlie")
            val coordinator = rig.coordinator()
            coordinator.rescan()
            block(
                rig,
                coordinator,
                rig.db
                    .documentDao()
                    .requireRowAtPath("c.pdf")
                    .docId,
            )
        }
}
