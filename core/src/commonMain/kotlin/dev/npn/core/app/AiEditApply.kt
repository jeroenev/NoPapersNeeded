package dev.npn.core.app

/**
 * One document's writable edit — see CONTEXT.md, "Writable Edit".
 *
 * This is the part of a suggestion that policy already permits writing: [addTags] and [removeTags]
 * have had new tags and removals dropped for the closed-vocabulary flows, and [properties] carry
 * the accepted field values (a null value clears it, as in [MetadataWriter.applyEdits]). A Writable
 * Edit is binary — a dropped item is simply absent — which is what separates it from Review
 * pre-selection's tri-state chips; the two must not be collapsed.
 *
 * [properties] is keyed by registry field key, exactly as [MetadataWriter.applyEdits] takes it;
 * what the AI may write is decided upstream by the run's request, not by the type here.
 */
data class WritableEdit(
    val docId: String,
    val addTags: List<String>,
    val removeTags: List<String>,
    val properties: Map<String, String?>,
) {
    val isEmpty: Boolean get() = addTags.isEmpty() && removeTags.isEmpty() && properties.isEmpty()
}

/**
 * The outcome of an [AiEditApply.apply], reported at the per-`(doc, item)` grain its callers need.
 *
 * [failures] carries the *cause* of each failed document, not just its id: the Edit Run wants
 * failure as data to rebuild a Retry review, but automatic enrichment catches the throwable to
 * drive its backoff, so swallowing the cause would be a regression in enrichment's error
 * reporting. Successes are the pairs actually written; a partially-failed apply leaves the
 * successes committed (no rollback), mirroring the bulk-op convention.
 */
data class ApplyReport(
    val addsWritten: Set<Pair<String, String>>, // (docId, tag)
    val removalsWritten: Set<Pair<String, String>>, // (docId, tag)
    val fieldsWritten: Set<Pair<String, String>>, // (docId, field key)
    val failures: Map<String, Throwable>, // docId -> cause
) {
    val failedDocs: Set<String> get() = failures.keys

    /** Total items (tags added, tags removed, fields) written across every document. */
    val appliedCount: Int get() = addsWritten.size + removalsWritten.size + fieldsWritten.size

    /** The documents that had at least one item written. */
    val changedDocs: Set<String>
        get() =
            (
                addsWritten.map { it.first } +
                    removalsWritten.map { it.first } +
                    fieldsWritten.map { it.first }
            ).toSet()
}

/**
 * The single home of the suggestion-write mechanics, shared by the reviewed [AiEditRun] and by
 * automatic [AiAutoEnricher]. Its callers differ in how they decide *what* to write, in what
 * shape they want failure back, and — crucially — in the *grain* at which a write must be atomic.
 * Both grains live here so the accounting and the failure-cause map stay shared; they are two
 * entry points, not one policy toggle, because the reason they
 * differ is domain, not configuration, and a strategy object would only hide that.
 *
 * ## [apply] — the batched, cross-document grain (the Edit Run)
 *
 * A reviewed run writes across MANY documents at once, and a given tag is typically shared by
 * several of them, so the natural transaction is *per distinct tag*, not per document:
 *
 * - Tag additions are grouped by distinct tag and written with one [MetadataWriter.addTag] per
 *   tag (a multi-doc op list committed in a single transaction). A tag's call is all-or-nothing:
 *   on failure every `(doc, tag)` pair for that tag is a failure, not a partial success. Removals
 *   are grouped and written the same way via [MetadataWriter.removeTag]. This grain is documented
 *   domain vocabulary — CONTEXT.md, "Apply": "Tag writes are grouped per distinct tag and are
 *   all-or-nothing per tag".
 * - Field values are inherently per document, so they are written with one
 *   [MetadataWriter.applyEdits] per document. A setter per field would re-run the storage path
 *   rules and rename the file once per field — up to three renames for one apply, each of which a
 *   synced library propagates — so this is a load-bearing invariant, not a style choice. That also
 *   makes field retry granularity per document, which is the honest grain: `applyEdits` is
 *   all-or-nothing, so a failure wrote none of that document's fields and all of them are owed.
 *   Deliberately still one call per document even though `applyEdits` takes many docs: the values
 *   differ per document, and one transaction over all of them would let a single rejected document
 *   lose every other document's write.
 * - Failures are collected into [ApplyReport], not thrown, and successes are never rolled back.
 *
 * Under this grain a document's tags and fields land in *separate* transactions (one per tag, plus
 * one for the fields). That is correct here precisely because the run is reviewed and multi-doc: a
 * partial failure becomes a Retry review of only the unwritten items, so splitting the write is
 * what lets the shared-tag transaction succeed for every other document even when one document's
 * field write is rejected.
 *
 * ## [applyAtomically] — the single-document, whole-edit grain (enrichment)
 *
 * Unattended enrichment writes exactly ONE document with no human in the loop, and on failure its
 * caller re-asks the model and retries. So the batched grain above is actively wrong for it: if
 * the tags committed but the field write failed, the retry would re-ask the model about a
 * half-written document. [applyAtomically] therefore folds the whole edit — tags and fields — into
 * a single [MetadataWriter.applyEdits] transaction, so a failure writes nothing and the retry
 * starts from the exact state the model was asked about.
 *
 * This stays above [MetadataWriter] and never reaches past it to the journal.
 */
class AiEditApply(
    private val meta: MetadataWriter,
) {
    /**
     * Write [edits], grouping tag ops by distinct tag and field ops by document as described on the
     * class. [onDocWritten] fires with each written document's id as its grouped op succeeds — for
     * a tag once per doc in the tag's list, for a field write once for that document — matching the
     * Edit Run's `_processed` update timing. The default no-op suits enrichment.
     *
     * This is the cross-document grain; for a single unattended document that must land whole or
     * not at all, use [applyAtomically].
     */
    suspend fun apply(
        edits: List<WritableEdit>,
        onDocWritten: (String) -> Unit = {},
    ): ApplyReport {
        val addsWritten = mutableSetOf<Pair<String, String>>()
        val removalsWritten = mutableSetOf<Pair<String, String>>()
        val fieldsWritten = mutableSetOf<Pair<String, String>>()
        val failures = mutableMapOf<String, Throwable>()

        // Group by tag so each distinct tag is written once for all its docs in a single journal
        // transaction (addTag/removeTag commit a multi-doc op list atomically). A tag's call is
        // all-or-nothing: on failure every (doc, tag) pair for that tag is retained for retry.
        val addsByTag = edits.flatMap { e -> e.addTags.map { e.docId to it } }.groupBy({ it.second }, { it.first })
        for ((tag, docIds) in addsByTag) {
            runCatching { meta.addTag(docIds, tag) }
                .onSuccess {
                    docIds.forEach { docId ->
                        addsWritten += docId to tag
                        onDocWritten(docId)
                    }
                }.onFailure { cause -> docIds.forEach { failures[it] = cause } }
        }

        val removalsByTag =
            edits.flatMap { e -> e.removeTags.map { e.docId to it } }.groupBy({ it.second }, { it.first })
        for ((tag, docIds) in removalsByTag) {
            runCatching { meta.removeTag(docIds, tag) }
                .onSuccess {
                    docIds.forEach { docId ->
                        removalsWritten += docId to tag
                        onDocWritten(docId)
                    }
                }.onFailure { cause -> docIds.forEach { failures[it] = cause } }
        }

        // Field values are per document: one applyEdits per document writes all of its fields as a
        // single change (see the class doc for why this is load-bearing, not a style choice).
        for (edit in edits) {
            if (edit.properties.isEmpty()) continue
            runCatching { meta.applyEdits(listOf(edit.docId), fields = edit.properties) }
                .onSuccess {
                    edit.properties.keys.forEach { field -> fieldsWritten += edit.docId to field }
                    onDocWritten(edit.docId)
                }.onFailure { cause -> failures[edit.docId] = cause }
        }

        return ApplyReport(addsWritten, removalsWritten, fieldsWritten, failures)
    }

    /**
     * Write one document's [edit] as a single all-or-nothing change: its tag additions AND its
     * field values in ONE [MetadataWriter.applyEdits] transaction, rather than the per-tag/per-doc
     * split [apply] uses.
     *
     * This is the grain unattended enrichment needs. That caller writes one document with no human
     * in the loop and, on failure, re-asks the model and retries. If tags could commit while the
     * field write failed (as they can under [apply]), the retry would re-ask the model about a
     * half-written document — precisely the state it must never observe. Folding everything into
     * one transaction makes a failure write nothing, so the retry starts from the exact state the
     * model was asked about.
     *
     * Removals are not carried: this grain exists for the additive enrichment pass, which never
     * removes tags. Passing an [edit] with [WritableEdit.removeTags] is a programming error. As in
     * [apply], the cause of a failed write is collected into [ApplyReport.failures] rather than
     * thrown, so the caller can re-raise the document's own cause to drive its backoff.
     */
    suspend fun applyAtomically(edit: WritableEdit): ApplyReport {
        require(edit.removeTags.isEmpty()) {
            "applyAtomically is for the additive enrichment grain and carries no removals"
        }
        val addsWritten = mutableSetOf<Pair<String, String>>()
        val fieldsWritten = mutableSetOf<Pair<String, String>>()
        val failures = mutableMapOf<String, Throwable>()

        // The whole edit — tags and fields together — as one applyEdits so a failure writes none
        // of it and a retry re-asks the model about the state it was originally shown.
        runCatching {
            meta.applyEdits(listOf(edit.docId), addTags = edit.addTags, fields = edit.properties)
        }.onSuccess {
            edit.addTags.forEach { tag -> addsWritten += edit.docId to tag }
            edit.properties.keys.forEach { field -> fieldsWritten += edit.docId to field }
        }.onFailure { cause -> failures[edit.docId] = cause }

        return ApplyReport(addsWritten, removalsWritten = emptySet(), fieldsWritten, failures)
    }
}
