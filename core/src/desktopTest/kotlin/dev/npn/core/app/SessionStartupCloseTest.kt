package dev.npn.core.app

import dev.npn.core.db.OcrPageCacheEntity
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SessionStartupCloseTest {
    @Test
    fun closingDuringTheFirstRescanReleasesEveryDrainerAndKeepsPaidWork() =
        runTest {
            val reached = CompletableDeferred<Unit>()
            val listing = CompletableDeferred<Unit>()
            val rig =
                AppSessionRig.create(backgroundScope, files = { fs ->
                    object : LibraryFileSystem by fs {
                        override suspend fun listAllIndexable(): List<FileMeta> {
                            reached.complete(Unit)
                            listing.await()
                            return fs.listAllIndexable()
                        }
                    }
                })
            try {
                rig.writePdf("scan.pdf", PdfFixtures.embeddedTextPdf("owed work"))
                val id = rig.docId("scan.pdf")
                val queue = rig.deviceDb.ocrQueueDao()
                queue.upsert(
                    OcrQueueEntity(id, QueueState.PENDING.column, attempts = 2, updatedAt = 1, aiRequested = true),
                )
                queue.cachePage(OcrPageCacheEntity(id, OcrEngineId.AI.column, "eng", 0, "paid page"))
                rig.session.start()
                reached.await()
                val drains =
                    listOf(
                        async(start = CoroutineStart.UNDISPATCHED) { rig.session.work.drainOcr() },
                        async(start = CoroutineStart.UNDISPATCHED) { rig.session.work.drainEnrichment() },
                        async(start = CoroutineStart.UNDISPATCHED) { rig.session.work.drainEditRuns() },
                    )
                drains.map { it.isCompleted } shouldBe listOf(false, false, false)

                rig.session.close()
                drains.awaitAll()

                listing.isCompleted shouldBe false
                val owed = queue.getByDocId(id).shouldNotBeNull()
                owed.aiRequested shouldBe true
                owed.attempts shouldBe 2
                queue.cachedPages(id, OcrEngineId.AI.column, "eng").single().text shouldBe "paid page"
            } finally {
                listing.complete(Unit)
                rig.close()
            }
        }
}
