package dev.npn.core.testing

import dev.npn.core.app.MetadataWriter

/**
 * One [MetadataWriter.applyEdits] call: the tags it added and the field values it set, and whether
 * it asked to fill only what each document lacks ([fillOnly]).
 */
data class BatchedEdit(
    val docIds: List<String>,
    val addTags: List<String>,
    val fields: Map<String, String?>,
    val fillOnly: Boolean = false,
) {
    /** The one document of a per-document apply. */
    val docId: String get() = docIds.single()
}

/**
 * Records every metadata write; each `fail*` hook makes the matching op throw.
 *
 * With [batchedFieldsOnly], a field setter throws and an [applyEdits] covering several documents
 * fails: for callers (AI edit runs, the enricher) that must write a document's fields in one
 * [applyEdits] per document. A setter per field would run the storage-path rules, and rename the
 * file, once per field; one call for several documents would let one rejection lose the others.
 */
class FakeMetadataWriter(
    private val batchedFieldsOnly: Boolean = false,
) : MetadataWriter {
    val addedTags = mutableListOf<Pair<List<String>, String>>()
    val removedTags = mutableListOf<Pair<List<String>, String>>()
    val reviewedSets = mutableListOf<Pair<List<String>, Boolean>>()

    /** Every setField call (both single- and multi-doc overloads) as (docIds, key, value). */
    val fieldSets = mutableListOf<Triple<List<String>, String, String?>>()

    /** Every [applyEdits] call, in the order applied. */
    val batchedEdits = mutableListOf<BatchedEdit>()

    /** If set, every batched op (addTag/removeTag/setReviewed/applyEdits) throws this. */
    var failBatchOps: (() -> Nothing)? = null

    /** addTag throws when any of these ids is in the call's docIds (per-doc partial-failure testing). */
    var failAddTagForDocs: Set<String> = emptySet()

    /** removeTag throws when any of these ids is in the call's docIds (per-doc partial-failure testing). */
    var failRemoveTagForDocs: Set<String> = emptySet()

    /** applyEdits throws when any of these ids is in the call's docIds (per-doc partial-failure testing). */
    var failApplyEditsForDocs: Set<String> = emptySet()

    /** If set, setField throws. */
    var failSetField: (() -> Nothing)? = null

    override suspend fun addTag(
        docIds: List<String>,
        tag: String,
    ) {
        failBatchOps?.invoke()
        if (docIds.any { it in failAddTagForDocs }) error("addTag failed for $docIds")
        addedTags += docIds to tag
    }

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) {
        failBatchOps?.invoke()
        if (docIds.any { it in failRemoveTagForDocs }) error("removeTag failed for $docIds")
        removedTags += docIds to tag
    }

    override suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    ) {
        failBatchOps?.invoke()
        reviewedSets += docIds to reviewed
    }

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) = setField(listOf(docId), key, value)

    override suspend fun setField(
        docIds: List<String>,
        key: String,
        value: String?,
    ) {
        check(!batchedFieldsOnly) { "field writes must batch through applyEdits" }
        failSetField?.invoke()
        fieldSets += Triple(docIds, key, value)
    }

    override suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String>,
        fields: Map<String, String?>,
        fillOnly: Boolean,
    ) {
        check(!batchedFieldsOnly || docIds.size == 1) { "one applyEdits per document, got $docIds" }
        failBatchOps?.invoke()
        if (docIds.any { it in failApplyEditsForDocs }) error("applyEdits failed for $docIds")
        batchedEdits += BatchedEdit(docIds, addTags, fields, fillOnly)
    }
}
