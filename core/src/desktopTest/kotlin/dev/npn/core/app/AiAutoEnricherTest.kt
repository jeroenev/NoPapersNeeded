package dev.npn.core.app

import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiField
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.MetadataSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.model.DocProperty
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeSingleton
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * What an automatic enrichment pass queues and writes. When it runs again after a failure is
 * [AiAutoEnricherRetryTest]; the shared fixtures live in AiAutoEnricherRig.kt.
 */
class AiAutoEnricherTest {
    /** The whole point of the queue: the OCR drain thread must never wait on the endpoint. */
    @Test
    fun `enqueue records the intent without calling the model`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")

                rig.enricher.enqueue("d1")

                rig.ai.suggestEditsCalls.shouldBeEmpty()
                rig.row("d1").shouldNotBeNull().attempts shouldBe 0
                rig.enrichedAt("d1").shouldBeNull()
            }
        }

    @Test
    fun `does not queue when the setting is off or the endpoint is unconfigured`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")

                rig.config = ACTIVE.copy(autoOnOcr = false)
                rig.enricher.enqueue("d1")
                rig.row("d1").shouldBeNull()

                // Automation on, but no model chosen: nothing to call.
                rig.config = ACTIVE.copy(model = "")
                rig.enricher.enqueue("d1")
                rig.row("d1").shouldBeNull()
            }
        }

    @Test
    fun `does not queue an already enriched or trashed document`() =
        runTest {
            withRig { rig ->
                rig.insert("done", aiEnrichedAt = 1L)
                rig.insert("bin", trashed = true)

                rig.enricher.enqueue("done")
                rig.enricher.enqueue("bin")

                rig.row("done").shouldBeNull()
                rig.row("bin").shouldBeNull()
            }
        }

    /** Re-OCR, sidecar adoption and engine re-selection all re-fire the hook for the same doc. */
    @Test
    fun `repeat enqueues never reset an existing row`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.ai.failWith = { error("endpoint down") }
                rig.enricher.enqueue("d1")
                rig.drain()
                val backedOff = rig.row("d1").shouldNotBeNull()

                rig.clock.advance(1_000L)
                rig.enricher.enqueue("d1")

                rig.row("d1").shouldNotBeNull() shouldBe backedOff
            }
        }

    // ---------- what a drain does ----------

    /**
     * The return value is what wakes the drain, so it must be true exactly when a row was newly
     * written. A false positive costs a pointless drain; a false negative leaves a document waiting
     * for the maintenance tick, since nothing observes the queue table.
     */
    @Test
    fun `enqueue reports new work only when it actually queued something`() =
        runTest {
            withRig { rig ->
                rig.insert("fresh")
                rig.insert("done", aiEnrichedAt = 1L)

                rig.enricher.enqueue("fresh") shouldBe true
                // Already queued: the row is left alone, so there is nothing new to wake up for.
                rig.enricher.enqueue("fresh") shouldBe false
                rig.enricher.enqueue("done") shouldBe false
                rig.enricher.enqueue("never-imported") shouldBe false

                rig.config = rig.config.copy(autoOnOcr = false)
                rig.insert("off")
                rig.enricher.enqueue("off") shouldBe false
                rig.db.aiEnrichQueueDao().countPending() shouldBe 1
            }
        }

    @Test
    fun `writes vocabulary tags and unfilled fields, then stamps and dequeues`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.ai.edit =
                    AiEditSuggestion(
                        addTags = listOf(TagSuggestion("invoice", isNew = false)),
                        removeTags = listOf("draft"),
                        metadata =
                            MetadataSuggestion(
                                title = "Electricity invoice",
                                correspondent = "Acme",
                                docDate = "2026-01-31",
                            ),
                    )
                rig.enricher.enqueue("d1")

                rig.drain() shouldBe 1

                // One write for the whole suggestion, so the file is renamed at most once. Removals
                // are dropped, not applied: the pass is additive even if the facade proposes them.
                val edit =
                    rig.meta.edits
                        .shouldBeSingleton()
                        .single()
                edit.docId shouldBe "d1"
                edit.addTags shouldContainExactly listOf("invoice")
                edit.properties shouldBe
                    mapOf(
                        DocProperty.TITLE to "Electricity invoice",
                        DocProperty.CORRESPONDENT to "Acme",
                        DocProperty.DOC_DATE to "2026-01-31",
                    )
                rig.enrichedAt("d1") shouldBe 5_000L
                rig.row("d1").shouldBeNull()
            }
        }

    /**
     * The invariant that silently broke once already: a pass carrying BOTH a vocabulary tag and a
     * field must commit them in exactly ONE write, not one per tag plus one for the fields. That
     * atomicity is what makes a failed pass all-or-nothing, so a retry re-asks the model about the
     * state it was originally shown rather than a half-written document. A regression to the
     * per-tag grain would produce more than one recorded edit and fail here.
     */
    @Test
    fun `commits a tag and a field together in exactly one write`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.ai.edit =
                    AiEditSuggestion(
                        addTags = listOf(TagSuggestion("invoice", isNew = false)),
                        metadata = MetadataSuggestion(title = "Electricity invoice"),
                    )
                rig.enricher.enqueue("d1")

                rig.drain() shouldBe 1

                // Exactly one write, carrying the tag AND the field. Two writes would mean the
                // enricher had regressed to the Edit Run's per-tag/per-doc split, losing atomicity.
                val edit =
                    rig.meta.edits
                        .shouldBeSingleton()
                        .single()
                edit.addTags shouldContainExactly listOf("invoice")
                edit.properties shouldBe mapOf(DocProperty.TITLE to "Electricity invoice")
            }
        }

    @Test
    fun `asks for one additive closed-vocabulary request covering tags and unfilled fields`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.enricher.enqueue("d1")

                rig.drain()

                rig.ai.suggestEditsCalls shouldContainExactly listOf("d1")
                val request = rig.ai.suggestEditsRequests.single()
                request.tags shouldBe true
                // No removals: nothing reviews this pass, so it must not undo a rule or a user.
                request.tagRemovals shouldBe false
                // 0 makes the library vocabulary a closed set for this unattended flow.
                request.maxNewTags shouldBe 0
                request.fields.map { it.field } shouldContainExactly
                    listOf(AiField.TITLE, AiField.CORRESPONDENT, AiField.DOC_DATE)
                // UNFILLED throughout: an automatic pass must never overwrite a human's value.
                request.fields.map { it.mode }.toSet() shouldBe setOf(AiFieldMode.UNFILLED)
            }
        }

    @Test
    fun `never writes a brand-new tag even when the facade proposes one`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.ai.edit =
                    AiEditSuggestion(
                        addTags =
                            listOf(
                                TagSuggestion("invoice", isNew = false),
                                TagSuggestion("brand-new-topic", isNew = true),
                            ),
                    )
                rig.enricher.enqueue("d1")

                rig.drain()

                rig.meta.addedTags shouldContainExactly listOf("d1" to listOf("invoice"))
            }
        }

    @Test
    fun `stamps a document the model had no suggestion for, so it is not queued again`() =
        runTest {
            withRig { rig ->
                rig.insert("d1")
                rig.ai.edit = AiEditSuggestion.EMPTY
                rig.enricher.enqueue("d1")

                rig.drain()

                // Nothing to write at all, so not even an empty transaction is committed.
                rig.meta.edits.shouldBeEmpty()
                rig.enrichedAt("d1").shouldNotBeNull()
                rig.row("d1").shouldBeNull()
            }
        }
}
