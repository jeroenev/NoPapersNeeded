package dev.npn.app

import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.core.app.AiOcrOffer
import dev.npn.core.app.AiOcrOutcome
import dev.npn.core.app.BakeFailure
import dev.npn.core.app.BakeOutcome
import dev.npn.core.app.RelocationOutcome
import dev.npn.core.app.SplitOutcome
import dev.npn.core.index.SplitFailure
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_rerun_ocr_failed
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_failed
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_no_endpoint
import no_papers_needed.composeapp.generated.resources.detail_bake_failed
import no_papers_needed.composeapp.generated.resources.detail_bake_failed_unsupported
import no_papers_needed.composeapp.generated.resources.detail_baked_in_place
import no_papers_needed.composeapp.generated.resources.detail_could_not_bake_copy
import no_papers_needed.composeapp.generated.resources.detail_exported_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_rename_invalid
import no_papers_needed.composeapp.generated.resources.detail_rename_taken
import no_papers_needed.composeapp.generated.resources.detail_rename_unsupported
import no_papers_needed.composeapp.generated.resources.detail_share_failed
import no_papers_needed.composeapp.generated.resources.detail_split_failed_unreadable
import no_papers_needed.composeapp.generated.resources.detail_trash_failed
import kotlin.test.Test

/**
 * The document actions menu (trash, rename, OCR, split, bake, export and share): each runs in the
 * viewmodel and reports through its message channel, so a result outlives the dialog that started it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocDetailActionsTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) =
        DocDetailViewModel("doc-1", session, aiConfiguredSettings())

    @Test
    fun trashCallsSessionAndCallback() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            var done = false
            model.trash { done = true }
            advanceUntilIdle()
            session.docLifecycle.trashedDocs shouldContainExactly listOf("doc-1")
            done shouldBe true
        }

    @Test
    fun trashFailureSkipsCallbackAndEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("doc-1") }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()
            var done = false
            model.trash { done = true }
            advanceUntilIdle()
            done shouldBe false
            messages shouldContainExactly listOf(failure(Res.string.detail_trash_failed, "trash failed for doc-1"))
        }

    @Test
    fun aRenameThatWorkedSaysNothing() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.rename("invoice.pdf")
            advanceUntilIdle()

            session.docLifecycle.renamedFiles shouldContainExactly listOf("doc-1" to "invoice.pdf")
            messages.shouldBeEmpty()
        }

    /** A library opened through Android's folder picker cannot rename a file, and the rename says so. */
    @Test
    fun aRenameTheLibraryCannotCarryOutSaysWhy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.relocation = RelocationOutcome.UNSUPPORTED }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.rename("invoice.pdf")
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.string.detail_rename_unsupported))
        }

    /** A name another file has, or one the library could not hold a document under, says which. */
    @Test
    fun aRenameOntoAnUnusableNameSaysWhy() =
        runTest(dispatcher) {
            for ((outcome, text) in listOf(
                RelocationOutcome.TAKEN to Res.string.detail_rename_taken,
                RelocationOutcome.INVALID_NAME to Res.string.detail_rename_invalid,
            )) {
                val session = FakeLibrarySession().apply { docLifecycle.relocation = outcome }
                val model = vm(session = session)
                val messages = collecting(model.messages.map { it.text })
                advanceUntilIdle()

                model.rename("invoice.pdf")
                advanceUntilIdle()

                messages shouldContainExactly listOf(uiText(text))
            }
        }

    @Test
    fun reOcrTogglesReindexingFlag() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.reOcr()
            advanceUntilIdle()
            session.ocr.reOcrIds shouldContainExactly listOf("doc-1")
            model.ui.value.reindexing shouldBe false
        }

    @Test
    fun reOcrFailureClearsFlagAndEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ocr.failReOcr = { error("ocr engine missing") } }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()
            model.reOcr()
            advanceUntilIdle()
            model.ui.value.reindexing shouldBe false
            messages shouldContainExactly listOf(failure(Res.string.common_rerun_ocr_failed, "ocr engine missing"))
        }

    @Test
    fun ocrWithAiQueuesTheDocumentAndClearsTheReindexingFlag() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ocr.aiOcr = AiOcrOffer.Ready }
            val model = vm(session = session)
            model.ocrWithAi()
            advanceUntilIdle()
            session.ocr.aiOcrIds shouldContainExactly listOf("doc-1")
            model.ui.value.reindexing shouldBe false
        }

    @Test
    fun ocrWithAiRunsALongDocumentOnceItIsConfirmed() =
        runTest(dispatcher) {
            // The confirmation lives in the UI, so the viewmodel must not add a second refusal
            // behind it: a confirmed long document queues like any other.
            val session = FakeLibrarySession().apply { ocr.aiOcr = AiOcrOffer.Large(pages = 214) }
            val model = vm(session = session)
            model.ocrWithAi()
            advanceUntilIdle()

            session.ocr.aiOcrIds shouldContainExactly listOf("doc-1")
            model.ui.value.reindexing shouldBe false
        }

    @Test
    fun aiOcrRefusedForWantOfAnEndpointSaysWhy() =
        runTest(dispatcher) {
            // The endpoint went away between offering the action and tapping it: the user has to
            // see why nothing happened rather than watch the action do nothing.
            val session =
                FakeLibrarySession().apply {
                    ocr.aiOcr = AiOcrOffer.Ready
                    ocr.aiOcrOutcome = AiOcrOutcome.NO_ENDPOINT
                }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()
            model.ocrWithAi()
            advanceUntilIdle()
            model.ui.value.reindexing shouldBe false
            messages shouldContainExactly listOf(uiText(Res.string.detail_ai_ocr_no_endpoint))
        }

    @Test
    fun aiOcrThatThrowsSurfacesAsAMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ocr.aiOcr = AiOcrOffer.Ready
                    ocr.failAiOcr = { error("Library session is closed") }
                }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()
            model.ocrWithAi()
            advanceUntilIdle()
            session.ocr.aiOcrIds shouldBe emptyList()
            model.ui.value.reindexing shouldBe false
            messages shouldContainExactly
                listOf(failure(Res.string.detail_ai_ocr_failed, "Library session is closed"))
        }

    @Test
    fun aSplitThatWorkedLeavesTheScreenWithoutAMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    maintenance.splitOutcome =
                        SplitOutcome.Done(
                            listOf("p1", "p2"),
                        )
                }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            var left = false

            model.split(listOf(0..0, 1..1)) { left = true }
            advanceUntilIdle()

            left shouldBe true
            messages shouldBe emptyList()
            model.ui.value.splitting shouldBe false
            session.maintenance.splits shouldContainExactly listOf("doc-1" to listOf(0..0, 1..1))
        }

    @Test
    fun aSplitThatFailedSaysWhyAndStays() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    maintenance.splitOutcome = SplitOutcome.Failed(SplitFailure.UNREADABLE_PDF)
                }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            var left = false

            model.split(listOf(0..0, 1..1)) { left = true }
            advanceUntilIdle()

            left shouldBe false
            messages shouldContainExactly listOf(uiText(Res.string.detail_split_failed_unreadable))
            model.ui.value.splitting shouldBe false
        }

    @Test
    fun aBakeThatWorkedSaysSo() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })

            model.bakeInPlace()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.string.detail_baked_in_place))
            session.content.bakedInPlace shouldContainExactly listOf("doc-1")
            model.ui.value.baking shouldBe false
        }

    @Test
    fun aBakeThatWasRefusedSaysWhy() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply { content.bakeResult = BakeOutcome.Failed(BakeFailure.UNSUPPORTED_PDF) }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })

            model.bakeInPlace()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.string.detail_bake_failed_unsupported))
            model.ui.value.baking shouldBe false
        }

    @Test
    fun aBakeThatThrewIsReported() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.failBake = { error("Library session is closed") } }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })

            model.bakeInPlace()
            advanceUntilIdle()

            messages shouldContainExactly listOf(failure(Res.string.detail_bake_failed, "Library session is closed"))
            model.ui.value.baking shouldBe false
        }

    @Test
    fun anExportWritesTheBakedCopyAndSaysSo() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.bakedBytes = byteArrayOf(1, 2, 3) }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            var written: ByteArray? = null

            model.exportBakedCopy { written = it }
            advanceUntilIdle()

            written?.toList() shouldBe listOf<Byte>(1, 2, 3)
            messages shouldContainExactly listOf(uiText(Res.string.detail_exported_baked_copy))
        }

    @Test
    fun aCopyThatCannotBeBakedIsNotDelivered() =
        runTest(dispatcher) {
            val model = vm()
            val messages = collecting(model.messages.map { it.text })
            var delivered = false

            model.shareBakedCopy { delivered = true }
            advanceUntilIdle()

            delivered shouldBe false
            messages shouldContainExactly listOf(uiText(Res.string.detail_could_not_bake_copy))
        }

    @Test
    fun aFailedShareSaysWhy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.bakedBytes = byteArrayOf(1) }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })

            model.shareBakedCopy { error("no app can receive it") }
            advanceUntilIdle()

            messages shouldContainExactly listOf(failure(Res.string.detail_share_failed, "no app can receive it"))
        }
}
