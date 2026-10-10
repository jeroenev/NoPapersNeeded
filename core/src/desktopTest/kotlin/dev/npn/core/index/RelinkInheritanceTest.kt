package dev.npn.core.index

import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * A relinked document's metadata and its predecessors' are one set of registers: the latest write
 * wins across all of them, a clear included, whichever way their ids sort.
 */
class RelinkInheritanceTest {
    /** After older->newer->current, the current document shows the newer one's title. */
    @Test
    fun theNewestValueInAChainWinsWhateverTheIds() =
        runTest {
            val x = "a".repeat(64)
            val y = "b".repeat(64)
            for ((older, newer) in listOf(x to y, y to x)) {
                withCurrent { rig, current ->
                    rig.commit(PendingOp.fieldSet(older, FieldKeys.TITLE, "Old"))
                    rig.commit(PendingOp.fieldSet(newer, FieldKeys.TITLE, "New"))
                    rig.commit(PendingOp.relink(older, newer))
                    rig.commit(PendingOp.relink(newer, current))

                    rig.rescan()

                    rig.db
                        .documentDao()
                        .getByDocId(current)
                        ?.title shouldBe "New"
                }
            }
        }

    /** Unpinning the OCR engine of a relinked document unpins the one it inherited. */
    @Test
    fun aClearMasksAnInheritedProperty() =
        runTest {
            withCurrent { rig, current ->
                val older = "a".repeat(64)
                rig.commit(PendingOp.propSet(older, SystemProperty.OCR_ENGINE, "tesseract"))
                rig.commit(PendingOp.relink(older, current))
                rig.rescan()
                rig.db
                    .documentDao()
                    .getByDocId(current)
                    ?.ocrEngineOverride shouldBe "tesseract"

                rig.commit(PendingOp.propSet(current, SystemProperty.OCR_ENGINE, null))
                rig.rescan()

                rig.db
                    .documentDao()
                    .getByDocId(current)
                    ?.ocrEngineOverride shouldBe null
            }
        }

    /** A rig whose library holds one document, `c.pdf`, handed to [block] with its id. */
    private suspend fun withCurrent(block: suspend (IndexRig, String) -> Unit) {
        val rig = IndexRig.create()
        try {
            rig.writePdf("c.pdf", "charlie")
            rig.rescan()
            block(
                rig,
                rig.db
                    .documentDao()
                    .rowAtPath("c.pdf")
                    .shouldNotBeNull()
                    .docId,
            )
        } finally {
            rig.close()
        }
    }

    /** Publish [op] as another device would, one clock tick after the last. */
    private suspend fun IndexRig.commit(op: PendingOp) {
        clock.advance()
        writer().commit(listOf(op))
    }
}
