@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.core.app

import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.FakeMetadataWriter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** A reviewed row with every listed item selected, as the user confirms it. */
private fun reviewed(
    docId: String,
    add: List<String> = emptyList(),
    remove: List<String> = emptyList(),
    fields: Map<String, String> = emptyMap(),
) = ReviewDoc(
    docId = docId,
    title = null,
    fileName = "$docId.pdf",
    snippet = null,
    chips = add.map { ReviewChip(it, isNew = false, selected = true) },
    removals = remove.map { ReviewChip(it, isNew = false, selected = true) },
    fields = fields.map { (key, value) -> ReviewField(key, key, value, current = null, selected = true) },
)

/**
 * How a reviewed batch is grouped into journal calls, and what a partly failed one hands back.
 * Scope accounting stays on [AiEditRun]; the two flows' end-to-end behaviour is in their own suites.
 */
class ReviewApplyTest {
    private val meta = FakeMetadataWriter(batchedFieldsOnly = true)
    private val apply = ReviewApply(meta)

    private suspend fun apply(vararg docs: ReviewDoc): ApplyOutcome = apply.apply(docs.toList()) { false }

    @Test
    fun `tag additions are grouped into one call per distinct tag`() =
        runTest {
            val outcome = apply(reviewed("a", add = listOf("shared", "onlyA")), reviewed("b", add = listOf("shared")))

            meta.addedTags shouldContainExactlyInAnyOrder listOf(listOf("a", "b") to "shared", listOf("a") to "onlyA")
            outcome shouldBe ApplyOutcome.Done(ApplyResult(applied = 3, docsChanged = 2, moreRemain = false))
        }

    @Test
    fun `tag removals are grouped the same way`() =
        runTest {
            apply(reviewed("a", remove = listOf("utility")), reviewed("b", remove = listOf("utility")))

            meta.removedTags shouldContainExactly listOf(listOf("a", "b") to "utility")
        }

    /**
     * Every field of a document is written as ONE edit, and documents one at a time: a setter per
     * field would rename the document once per field, and one edit over several documents would let
     * one rejection lose the others' writes.
     */
    @Test
    fun `fields are one edit per document`() =
        runTest {
            val fields = mapOf(FieldKeys.CORRESPONDENT to "Acme", FieldKeys.DOC_DATE to "2024-01-15")

            val outcome = apply(reviewed("a", fields = fields), reviewed("b", fields = fields))

            meta.batchedEdits.map { it.docId to it.fields } shouldContainExactly listOf("a" to fields, "b" to fields)
            outcome.shouldBeInstanceOf<ApplyOutcome.Done>().result.applied shouldBe 4
        }

    @Test
    fun `a batch with nothing selected writes nothing`() =
        runTest {
            val unticked =
                reviewed("a", add = listOf("t")).let { it.copy(chips = it.chips.map { c -> c.copy(selected = false) }) }

            apply(unticked) shouldBe ApplyOutcome.NothingSelected

            meta.addedTags shouldBe emptyList()
        }

    /** The retry holds only what is still owed, and the Done that ends it counts every attempt. */
    @Test
    fun `a partly failed batch retries only what failed and counts it all at the end`() =
        runTest {
            meta.failAddTagForDocs = setOf("b")

            val retry =
                apply(reviewed("a", add = listOf("t1")), reviewed("b", add = listOf("t2"), fields = mapOf("k" to "v")))
                    .shouldBeInstanceOf<ApplyOutcome.Retry>()

            retry.failedDocs shouldBe 1
            val remaining = retry.remaining.single()
            remaining.chips.single().selected shouldBe true
            // The field landed, so the retry must not write it again.
            remaining.fields.single().selected shouldBe false

            meta.failAddTagForDocs = emptySet()
            apply(remaining) shouldBe ApplyOutcome.Done(ApplyResult(applied = 3, docsChanged = 1, moreRemain = false))
        }
}
