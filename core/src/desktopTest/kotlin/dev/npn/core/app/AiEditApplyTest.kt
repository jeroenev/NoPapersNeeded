@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.core.app

import dev.npn.core.ai.AiField
import dev.npn.core.model.DocProperty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

// Records the grouped writes AiEditApply issues; setters must not be used.
private class ApplyMeta : MetadataWriter {
    val addedTags = mutableListOf<Pair<List<String>, String>>()
    val removedTags = mutableListOf<Pair<List<String>, String>>()

    /** Field writes, in the order applied: one entry per document, never one per field. */
    val batchedEdits = mutableListOf<Pair<String, Map<DocProperty, String?>>>()

    override suspend fun addTag(
        docIds: List<String>,
        tag: String,
    ) {
        addedTags += docIds to tag
    }

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) {
        removedTags += docIds to tag
    }

    override suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String>,
        properties: Map<DocProperty, String?>,
    ) {
        // Field values are per document, so an apply must never fold several into one call: that
        // would make one document's rejection lose the others' writes.
        batchedEdits += docIds.single() to properties
    }

    // Field writes must go through applyEdits: a setter per field would re-run the storage path
    // rules once per field and rename the document repeatedly for one apply.
    override suspend fun setTitle(
        docId: String,
        title: String?,
    ) = error("must batch through applyEdits")

    override suspend fun setCorrespondent(
        docIds: List<String>,
        value: String?,
    ) = error("must batch through applyEdits")

    override suspend fun setDocDate(
        docId: String,
        isoDate: String?,
    ) = error("must batch through applyEdits")

    override suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    ) = error("unused")

    override suspend fun setDocType(
        docIds: List<String>,
        value: String?,
    ) = error("unused")

    override suspend fun setNotes(
        docId: String,
        notes: String?,
    ) = error("unused")

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) = error("unused")
}

private fun writableEdit(
    docId: String,
    addTags: List<String> = emptyList(),
    removeTags: List<String> = emptyList(),
    properties: Map<AiField, String?> = emptyMap(),
) = WritableEdit(docId, addTags, removeTags, properties)

/**
 * The write mechanics shared by the Edit Run and by automatic enrichment: how selected items are
 * grouped into journal calls. Selection and Retry accounting stay on [AiEditRun].
 */
class AiEditApplyTest {
    @Test
    fun applyGroupsAddsByTagIntoOneCallPerTag() =
        runTest {
            // a and b both add "shared"; a also adds "onlyA". Expect one grouped call per distinct
            // tag, not one per (doc, tag) pair.
            val meta = ApplyMeta()
            val apply = AiEditApply(meta)

            val report =
                apply.apply(
                    listOf(
                        writableEdit("a", addTags = listOf("shared", "onlyA")),
                        writableEdit("b", addTags = listOf("shared")),
                    ),
                )

            meta.addedTags shouldContainExactlyInAnyOrder
                listOf(
                    listOf("a", "b") to "shared",
                    listOf("a") to "onlyA",
                )
            report.appliedCount shouldBe 3
            report.changedDocs shouldBe setOf("a", "b")
            report.failedDocs shouldBe emptySet()
        }

    @Test
    fun applyWritesSelectedRemovalsGroupedByTag() =
        runTest {
            // "utility" is on both docs -> one grouped removeTag call for both.
            val meta = ApplyMeta()
            val apply = AiEditApply(meta)

            val report =
                apply.apply(
                    listOf(
                        writableEdit("a", removeTags = listOf("utility")),
                        writableEdit("b", removeTags = listOf("utility")),
                    ),
                )

            meta.removedTags shouldContainExactly listOf(listOf("a", "b") to "utility")
            report.appliedCount shouldBe 2
            report.changedDocs shouldBe setOf("a", "b")
        }

    /**
     * Every field of a document is written as ONE edit. A setter per field would re-run the storage
     * path rules per field, renaming the document up to three times for one apply and propagating
     * each intermediate name to every replica.
     */
    @Test
    fun applyWritesAllOfADocumentsFieldsAsOneEdit() =
        runTest {
            val meta = ApplyMeta()
            val apply = AiEditApply(meta)

            val report =
                apply.apply(
                    listOf(
                        writableEdit(
                            "a",
                            properties =
                                mapOf(
                                    AiField.CORRESPONDENT to "Acme",
                                    AiField.DOC_DATE to "2024-01-15",
                                ),
                        ),
                    ),
                )

            meta.batchedEdits shouldContainExactly
                listOf(
                    "a" to
                        mapOf(
                            DocProperty.CORRESPONDENT to "Acme",
                            DocProperty.DOC_DATE to "2024-01-15",
                        ),
                )
            // Both fields still count as applied; batching changes the write, not the tally.
            report.appliedCount shouldBe 2
            report.fieldsWritten shouldContainExactlyInAnyOrder
                listOf("a" to AiField.CORRESPONDENT, "a" to AiField.DOC_DATE)
        }

    /** Documents are written one at a time, so one document's failure cannot cost another's. */
    @Test
    fun applyWritesOneEditPerDocument() =
        runTest {
            val meta = ApplyMeta()
            val apply = AiEditApply(meta)

            apply.apply(
                listOf(
                    writableEdit("a", properties = mapOf(AiField.CORRESPONDENT to "Acme")),
                    writableEdit("b", properties = mapOf(AiField.CORRESPONDENT to "Acme")),
                ),
            )

            meta.batchedEdits.map { it.first } shouldContainExactly listOf("a", "b")
        }

    @Test
    fun anEmptyEditListCommitsNothing() =
        runTest {
            val meta = ApplyMeta()
            val apply = AiEditApply(meta)

            val report = apply.apply(emptyList())

            meta.addedTags shouldBe emptyList()
            meta.removedTags shouldBe emptyList()
            meta.batchedEdits shouldBe emptyList()
            report.appliedCount shouldBe 0
            report.failures.shouldBeEmpty()
        }
}
