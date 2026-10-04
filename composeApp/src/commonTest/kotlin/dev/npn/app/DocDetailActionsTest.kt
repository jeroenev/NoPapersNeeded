package dev.npn.app

import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.core.app.AiOcrOffer
import dev.npn.core.app.AiOcrOutcome
import dev.npn.core.app.BakeFailure
import dev.npn.core.app.BakeOutcome
import dev.npn.core.app.SplitFailure
import dev.npn.core.app.SplitOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The document actions menu (trash, OCR, split, bake, export and share): each runs in the viewmodel
 * and reports through its message channel, so a result outlives the dialog that started it.
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
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            var done = false
            model.trash { done = true }
            advanceUntilIdle()
            done shouldBe false
            messages shouldContainExactly listOf("Could not move to trash: trash failed for doc-1")
            job.cancel()
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
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.reOcr()
            advanceUntilIdle()
            model.ui.value.reindexing shouldBe false
            messages shouldContainExactly listOf("Could not re-run OCR: ocr engine missing")
            job.cancel()
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
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.ocrWithAi()
            advanceUntilIdle()
            model.ui.value.reindexing shouldBe false
            messages shouldContainExactly listOf("Set up an AI assistant in Settings to read text with it.")
            job.cancel()
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
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.ocrWithAi()
            advanceUntilIdle()
            session.ocr.aiOcrIds shouldBe emptyList()
            model.ui.value.reindexing shouldBe false
            messages shouldContainExactly listOf("Could not read text with the assistant: Library session is closed")
            job.cancel()
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
            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }
            var left = false

            model.split(listOf(0..0, 1..1)) { left = true }
            advanceUntilIdle()
            collector.cancel()

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
            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }
            var left = false

            model.split(listOf(0..0, 1..1)) { left = true }
            advanceUntilIdle()
            collector.cancel()

            left shouldBe false
            messages shouldContainExactly listOf("Could not split the document: the PDF could not be read.")
            model.ui.value.splitting shouldBe false
        }

    @Test
    fun aBakeThatWorkedSaysSo() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }

            model.bakeInPlace()
            advanceUntilIdle()
            collector.cancel()

            messages shouldContainExactly listOf("Original PDF updated")
            session.content.bakedInPlace shouldContainExactly listOf("doc-1")
            model.ui.value.baking shouldBe false
        }

    @Test
    fun aBakeThatWasRefusedSaysWhy() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply { content.bakeResult = BakeOutcome.Failed(BakeFailure.UNSUPPORTED_PDF) }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }

            model.bakeInPlace()
            advanceUntilIdle()
            collector.cancel()

            val refused = "Could not update the original PDF: this PDF cannot carry the tags and details."
            messages shouldContainExactly listOf("$refused It was left unchanged.")
            model.ui.value.baking shouldBe false
        }

    @Test
    fun aBakeThatThrewIsReported() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.failBake = { error("Library session is closed") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }

            model.bakeInPlace()
            advanceUntilIdle()
            collector.cancel()

            messages shouldContainExactly listOf("Could not update the original PDF: Library session is closed")
            model.ui.value.baking shouldBe false
        }

    @Test
    fun anExportWritesTheBakedCopyAndSaysSo() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.bakedBytes = byteArrayOf(1, 2, 3) }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }
            var written: ByteArray? = null

            model.exportBakedCopy { written = it }
            advanceUntilIdle()
            collector.cancel()

            written?.toList() shouldBe listOf<Byte>(1, 2, 3)
            messages shouldContainExactly listOf("PDF exported with tags and details")
        }

    @Test
    fun aCopyThatCannotBeBakedIsNotDelivered() =
        runTest(dispatcher) {
            val model = vm()
            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }
            var delivered = false

            model.shareBakedCopy { delivered = true }
            advanceUntilIdle()
            collector.cancel()

            delivered shouldBe false
            messages shouldContainExactly listOf("Could not prepare PDF for export")
        }

    @Test
    fun aFailedShareSaysWhy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.bakedBytes = byteArrayOf(1) }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }

            model.shareBakedCopy { error("no app can receive it") }
            advanceUntilIdle()
            collector.cancel()

            messages shouldContainExactly listOf("Share failed: no app can receive it")
        }
}
