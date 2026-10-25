package dev.npn.core.index

import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * A relinked document's metadata and its predecessors' are one set of registers: the latest write
 * wins across all of them, a clear included, whichever way their ids sort. The relink writes nothing
 * but the `relink` event.
 */
class RelinkInheritanceTest {
    /** After older->newer->current, the current document shows the title newer was given after its relink. */
    @Test
    fun aChainCarriesTheLatestValueToItsEnd() =
        runTest {
            withCurrent { rig, coordinator, current ->
                val older = "a".repeat(64)
                val newer = "b".repeat(64)
                coordinator.commitAndApply(listOf(PendingOp.fieldSet(older, FieldKeys.TITLE, "Old")))
                coordinator.commitAndRescan(listOf(PendingOp.relink(older, newer)))
                coordinator.commitAndApply(listOf(PendingOp.fieldSet(newer, FieldKeys.TITLE, "New")))
                coordinator.commitAndRescan(listOf(PendingOp.relink(newer, current)))

                rig.rescan()

                rig.db
                    .documentDao()
                    .getByDocId(current)
                    ?.title shouldBe "New"
            }
        }

    /** Unpinning the OCR engine of a relinked document unpins the one it inherited, for good. */
    @Test
    fun aClearOnTheTargetMasksAnInheritedProperty() =
        runTest {
            withCurrent { rig, coordinator, current ->
                val older = "a".repeat(64)
                coordinator.commitAndApply(listOf(PendingOp.propSet(older, SystemProperty.OCR_ENGINE, "tesseract")))
                coordinator.commitAndRescan(listOf(PendingOp.relink(older, current)))
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

    /**
     * A document relinked twice, z onto b1 and b1 onto the current one, shows z's title, tag and
     * storage path: the whole chain is its own.
     */
    @Test
    fun aRelinkOfARelinkTargetShowsTheWholeChain() =
        runTest {
            withCurrent { rig, coordinator, current ->
                val z = "a".repeat(64)
                val b1 = "b".repeat(64)
                coordinator.commitAndApply(
                    listOf(
                        PendingOp.fieldSet(z, FieldKeys.TITLE, "Water bill"),
                        PendingOp.tagAdd(z, "filed"),
                        PendingOp.propSet(z, SystemProperty.STORAGE_PATH, "c.pdf"),
                    ),
                )
                coordinator.commitAndRescan(listOf(PendingOp.relink(z, b1)))

                coordinator.commitAndRescan(listOf(PendingOp.relink(b1, current)))

                val row =
                    rig.db
                        .documentDao()
                        .getByDocId(current)
                        .shouldNotBeNull()
                row.title shouldBe "Water bill"
                row.relPath shouldBe "c.pdf"
                rig.db.tagDao().tagsForDocument(current) shouldContainExactly listOf("filed")
                coordinator.commitBuilt { state ->
                    state.inheritedProp(current, SystemProperty.STORAGE_PATH) shouldBe "c.pdf"
                    state.effectiveProp(current, SystemProperty.STORAGE_PATH) shouldBe null
                    emptyList()
                }
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
