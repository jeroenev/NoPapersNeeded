package dev.npn.core.app

import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.model.FieldKeys
import dev.npn.core.work.QueueRetryPolicy
import dev.npn.core.work.backoffMs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.days

/**
 * When a queued document is tried again: drain order, backoff, and the states that pause the drain
 * without spending an attempt. What a successful pass writes is [AiAutoEnricherTest].
 */
class AiAutoEnricherRetryTest {
    @Test
    fun `drains the whole backlog in one go`() =
        runTest {
            withRig { rig ->
                rig.insertAndQueue("d1", "d2", "d3")

                rig.drain() shouldBe 3

                rig.ai.suggestEditsCalls shouldContainExactly listOf("d1", "d2", "d3")
            }
        }

    /** A document trashed or enriched by hand while it waited is no longer worth a call. */
    @Test
    fun `drops a queued document that stopped being enrichable`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.enricher.enqueue("d1")
                rig.trash("d1")

                rig.drain() shouldBe 1

                rig.ai.suggestEditsCalls.shouldBeEmpty()
                rig.row("d1").shouldBeNull()
            }
        }

    /** Queue rows are the whole point: work survives the process that queued it. */
    @Test
    fun `a restart resumes work queued by the previous run`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.newEnricher().enqueue("d1")

                // Nothing carries over from the enricher that queued it.
                rig.drain(rig.newEnricher()) shouldBe 1

                rig.enrichedAt("d1").shouldNotBeNull()
            }
        }

    @Test
    fun `a failed call keeps the row, backs it off and leaves the document unstamped`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.enricher.enqueue("d1")
                rig.ai.failWith = { error("endpoint down") }

                rig.drain()

                val row = rig.row("d1").shouldNotBeNull()
                row.attempts shouldBe 1
                row.nextAttemptAt shouldBe 5_000L + backoffMs(1)
                row.lastError shouldBe "endpoint down"
                rig.enrichedAt("d1").shouldBeNull()
            }
        }

    /**
     * The queue must back off on the write's own cause: regressing to a synthetic one (or
     * swallowing it) would blind the backoff and the row's `lastError`.
     */
    @Test
    fun `a failed write backs off on the write's own cause, not a synthetic one`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.ai.edit =
                    AiEditSuggestion(metadata = FieldSuggestion(mapOf(FieldKeys.TITLE to "Electricity invoice")))
                rig.enricher.enqueue("d1")
                rig.meta.failEditsWith = { IllegalStateException("journal rejected the write") }

                rig.drain()

                val row = rig.row("d1").shouldNotBeNull()
                row.attempts shouldBe 1
                row.nextAttemptAt shouldBe 5_000L + backoffMs(1)
                row.lastError shouldBe "journal rejected the write"
                rig.enrichedAt("d1").shouldBeNull()
            }
        }

    @Test
    fun `does not retry before the backoff has elapsed, then succeeds`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.enricher.enqueue("d1")
                rig.ai.failWith = { error("endpoint down") }

                // Twice, so the row's backoff (2m) outlasts the cooldown (1m) and it is the row
                // being not-yet-due that holds the retry back, not the endpoint cooldown.
                rig.drain()
                rig.clock.advance(backoffMs(1))
                rig.drain()
                rig.ai.suggestEditsCalls.size shouldBe 2

                // Still inside the backoff window: nothing is due.
                rig.clock.advance(backoffMs(2) - 1)
                rig.drain() shouldBe 0
                rig.ai.suggestEditsCalls.size shouldBe 2

                rig.clock.advance(1)
                rig.ai.failWith = null
                rig.drain() shouldBe 1
                rig.enrichedAt("d1").shouldNotBeNull()
                rig.row("d1").shouldBeNull()
            }
        }

    /** An outage must not fire the entire backlog at a dead endpoint one document at a time. */
    @Test
    fun `stops the drain after a failure instead of walking the backlog`() =
        runTest {
            withRig { rig ->
                rig.insertAndQueue("d1", "d2", "d3")
                rig.ai.failWith = { error("endpoint down") }

                rig.drain() shouldBe 0

                rig.ai.suggestEditsCalls shouldContainExactly listOf("d1")
                rig.row("d2").shouldNotBeNull().attempts shouldBe 0
            }
        }

    /**
     * The failing document backs off, so a later kick reaches the rest of the queue — one document
     * the endpoint always rejects must not strand every other document behind it.
     *
     * It does cost the queue the cooldown, because a failed call is not attributable: this looks
     * exactly like the start of an outage. That is the priced-in half of the trade, so it is
     * asserted rather than skipped over.
     */
    @Test
    fun `a poison document delays the queue by the cooldown but does not block it`() =
        runTest {
            withRig { rig ->
                rig.insertAndQueue("bad", "good")
                rig.ai.failWith = { if (it == "bad") error("bad doc") }

                // Trips on "bad" and stops, taking the queue with it for the cooldown.
                rig.drain() shouldBe 0
                rig.drain() shouldBe 0
                rig.ai.suggestEditsCalls shouldContainExactly listOf("bad")

                // Once it lifts, "good" is served: "bad" has been pushed out to its own retry time.
                rig.clock.advance(QueueRetryPolicy.ENDPOINT_COOLDOWN_MS)
                rig.drain() shouldBe 1

                rig.enrichedAt("good").shouldNotBeNull()
                rig.enrichedAt("bad").shouldBeNull()
            }
        }

    /**
     * Stopping the drain does not on its own stop an outage from being retried per document: each
     * newly OCR'd document kicks its own drain, and never-tried rows are served first, so an import
     * against a dead endpoint would spend one call per document. The cooldown is what caps that.
     */
    @Test
    fun `an import against a dead endpoint costs one call per cooldown, not one per document`() =
        runTest {
            withRig { rig ->
                rig.ai.failWith = { error("endpoint down") }

                // 20 documents arriving the way an import delivers them: OCR'd one by one, each
                // queued and each kicking the drain itself.
                repeat(20) { i ->
                    rig.insert("d$i")
                    rig.enricher.enqueue("d$i") shouldBe true
                    rig.drain()
                    rig.clock.advance(5_000L)
                }

                // 100s of imports at one minute of cooldown: two calls, not twenty.
                rig.ai.suggestEditsCalls.size shouldBe 2
                // Nothing is dropped — every document is still owed its pass.
                rig.deviceDb
                    .aiEnrichQueueDao()
                    .observeQueue()
                    .first()
                    .size shouldBe 20
            }
        }

    /** The cooldown is an outage brake, not a rate limit: success clears it immediately. */
    @Test
    fun `a success clears the cooldown for the next document`() =
        runTest {
            withRig { rig ->
                rig.insertAndQueue("bad", "good1", "good2")
                rig.ai.failWith = { if (it == "bad") error("bad doc") }
                rig.drain() shouldBe 0

                rig.clock.advance(QueueRetryPolicy.ENDPOINT_COOLDOWN_MS)

                // Both good documents in one drain: the first success lifts the cooldown, so the
                // second is not made to wait out another one.
                rig.drain() shouldBe 2
                rig.enrichedAt("good1").shouldNotBeNull()
                rig.enrichedAt("good2").shouldNotBeNull()
            }
        }

    /**
     * A retry that has come due must not spend the kick a fresh import needs. The drain stops at
     * the first failure, so serving the older-but-failing row first would cost the new document its
     * enrichment for a whole kick cycle — on Android, the rest of that maintenance window.
     */
    @Test
    fun `a due retry is served after work that has never been tried`() =
        runTest {
            withRig { rig ->
                rig.insertAndQueue("bad")
                rig.ai.failWith = { if (it == "bad") error("bad doc") }
                rig.drain() shouldBe 0

                // "bad" is due again by the time the fresh document arrives.
                rig.clock.advance(backoffMs(1))
                rig.insertAndQueue("good")

                rig.drain() shouldBe 1
                rig.ai.suggestEditsCalls shouldContainExactly listOf("bad", "good", "bad")
                rig.enrichedAt("good").shouldNotBeNull()
            }
        }

    /**
     * A long outage must not cost a document its enrichment. The backoff settles at a daily
     * ceiling and the row keeps its place, so however long the endpoint is gone, the pass still
     * happens once it comes back.
     */
    @Test
    fun `keeps retrying at the ceiling instead of giving up`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.enricher.enqueue("d1")
                rig.ai.failWith = { error("endpoint down") }

                // A fortnight of daily retries, well past any attempt cap.
                repeat(14) {
                    rig.clock.advance(1.days.inWholeMilliseconds)
                    rig.drain() shouldBe 0
                }
                rig.ai.suggestEditsCalls.size shouldBe 14
                rig.row("d1").shouldNotBeNull().attempts shouldBe 14

                // The endpoint comes back: still enriched, nothing lost.
                rig.clock.advance(1.days.inWholeMilliseconds)
                rig.ai.failWith = null
                rig.drain() shouldBe 1
                rig.enrichedAt("d1").shouldNotBeNull()
                rig.row("d1").shouldBeNull()
            }
        }

    /** Turning the feature off (or losing the endpoint config) must not consume the retries. */
    @Test
    fun `waits without burning attempts while the feature is off`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.enricher.enqueue("d1")

                rig.config = ACTIVE.copy(autoOnOcr = false)
                rig.drain() shouldBe 0
                rig.ai.suggestEditsCalls.shouldBeEmpty()
                rig.row("d1").shouldNotBeNull().attempts shouldBe 0

                rig.config = ACTIVE
                rig.drain() shouldBe 1
                rig.enrichedAt("d1").shouldNotBeNull()
            }
        }

    /**
     * What a caller that keeps a process alive by draining relies on (the Android maintenance job):
     * a backlog waiting out a backoff ends the drain instead of holding the caller for the whole
     * backoff window. The row stays owed, for a later drain to pick up.
     */
    @Test
    fun `a drain returns rather than waiting out a backoff`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.insert("d2")
                rig.enricher.enqueue("d1")
                rig.enricher.enqueue("d2")
                rig.ai.failWith = { error("endpoint down") }

                rig.drain()

                // One request, not one per document: the drain stops at the first failure.
                rig.ai.suggestEditsCalls.size shouldBe 1
                // And a second drain at the same instant returns at once rather than sleeping on
                // either the cooldown or the row's backoff.
                rig.drain() shouldBe 0
                rig.deviceDb
                    .aiEnrichQueueDao()
                    .observeQueue()
                    .first()
                    .size shouldBe 2
            }
        }
}
