package dev.npn.core.app

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.testing.FakeDocContentFacade
import dev.npn.core.testing.FakeDocumentRepository
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.listItem
import dev.npn.core.work.QueueRetryPolicy
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The two AI queues call one endpoint, so they share its cooldown: a failure in either holds the
 * other back too, rather than each probing an endpoint that is down on its own schedule.
 */
class EndpointCooldownTest {
    private fun Rig.editQueue(): AiEditQueue {
        val documents = FakeDocumentRepository().apply { documents.value = listOf(listItem("a")) }
        return AiEditQueue(
            dao = deviceDb.aiEditJobDao(),
            documents = documents,
            content = FakeDocContentFacade(),
            meta = FakeMetadataWriter(batchedFieldsOnly = true),
            ai = ai,
            fieldRegistry = fieldRegistry,
            readConfig = { config },
            clock = clock,
            health = health,
            onEnqueued = {},
        )
    }

    @Test
    fun `a failed enrichment holds queued edit runs back until the cooldown has passed`() =
        runTest {
            withRig { rig ->
                val edits = rig.editQueue()
                edits.enqueueEditRun(listOf("a"), AiEditRequest())
                rig.insertAndQueue("d1")
                rig.ai.failWith = { error("endpoint down") }
                rig.drain() shouldBe 0
                rig.ai.failWith = null

                edits.processNext() shouldBe false
                rig.ai.suggestEditsCalls shouldContainExactly listOf("d1")

                rig.clock.advance(QueueRetryPolicy.ENDPOINT_COOLDOWN_MS)
                edits.processNext() shouldBe true
                rig.ai.suggestEditsCalls shouldContainExactly listOf("d1", "a")
            }
        }

    @Test
    fun `a failed edit run holds enrichment back, and an answer to either lifts it for both`() =
        runTest {
            withRig { rig ->
                val edits = rig.editQueue()
                edits.enqueueEditRun(listOf("a"), AiEditRequest())
                rig.insertAndQueue("d1")
                rig.ai.failWith = { if (it == "a") error("endpoint down") }
                edits.processNext() shouldBe false

                rig.drain() shouldBe 0
                rig.ai.suggestEditsCalls shouldContainExactly listOf("a")

                // The endpoint answers the edit queue's retry; enrichment need not wait any longer.
                rig.ai.failWith = null
                rig.clock.advance(QueueRetryPolicy.ENDPOINT_COOLDOWN_MS + 60_000L)
                edits.processNext() shouldBe true
                rig.drain() shouldBe 1
                rig.ai.suggestEditsCalls shouldContainExactly listOf("a", "a", "d1")
            }
        }
}
