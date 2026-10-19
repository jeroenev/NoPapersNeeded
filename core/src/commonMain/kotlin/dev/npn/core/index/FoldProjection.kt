package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldEntity
import dev.npn.core.db.FtsDocumentEntity
import dev.npn.core.db.FtsRows
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.TagEntity
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldValues
import dev.npn.core.model.SystemProperty

/** Which documents one [FoldProjection.apply] projects, and what else it takes into account. */
internal sealed interface FoldScope {
    /**
     * A rescan's fold: every document, preferring a document's own bytes, found back in the
     * library, over a journal that hides it (see [FoldPass.lifecycle]). [contentAt] is the
     * content hash of every listed file the rescan knows, by path (`knownHashes` in [Indexer]).
     */
    class FullRescan(
        val contentAt: Map<String, String>,
    ) : FoldScope

    /**
     * A fold of the journal alone, with no listing of the folder at hand: it never prefers a file
     * over the journal, so it resurrects nothing ([FoldProjection.apply]).
     */
    sealed interface Journal : FoldScope

    /**
     * The documents [docs] and the tags [styledTags] some events touched. [preserveLifecycleDocs]
     * keep the lifecycle their row already has, which a commit wrote optimistically.
     */
    class Events(
        val docs: Set<String>,
        val styledTags: Set<String>,
        val preserveLifecycleDocs: Set<String>,
    ) : Journal {
        companion object {
            /**
             * The scope of [events], already reduced into [state]: their documents and styled
             * tags, plus what a rename, merge, relink, tag removal or field definition fans out to,
             * as a full fold projects them.
             */
            fun of(
                state: FoldedState,
                events: List<JournalEvent>,
                preserveLifecycleDocs: Set<String>,
            ): Events {
                val documents = LinkedHashSet<String>()
                val styledTags = LinkedHashSet<String>()
                for (event in events) {
                    event.doc?.let(documents::add)
                    // Rename/merge/relink/style, and a removal an earlier build wrote, fan out to
                    // more docs/tags.
                    when (val op = event.op) {
                        is JournalOp.TagRename, is JournalOp.TagMerge -> {
                            documents.addAll(state.taggedDocs())
                        }

                        is JournalOp.TagStyle -> {
                            styledTags += op.tag
                        }

                        // Its old document (event.doc) is superseded; the new one's metadata is its
                        // own, copied in the same transaction, so no other row changes.
                        is JournalOp.Relink -> {
                            documents += op.newDoc
                        }

                        // Builds before relink copies listed, in a removal from a relink target,
                        // its predecessors' dots too, so their rows change with it.
                        is JournalOp.TagRemove -> {
                            event.doc?.let { documents += state.predecessorsOf(state.resolveDocId(it)) }
                        }

                        // A definition change is library-wide: every document's projection of that
                        // field depends on its type (the numeric shadow), so all docs with metadata
                        // are reprojected rather than only the event's own document (there is none).
                        is JournalOp.FieldDefSet -> {
                            documents.addAll(state.docsWithMetadata())
                        }

                        // Their own document is all they touch, or they touch none (a setting, or an
                        // op from a newer build, which folds to nothing).
                        is JournalOp.TagAdd, is JournalOp.PropSet, is JournalOp.FieldSet,
                        is JournalOp.DocTrash, JournalOp.DocRestore, JournalOp.DocDelete, JournalOp.DocUndelete,
                        is JournalOp.SettingSet, is JournalOp.Unknown,
                        -> {}
                    }
                }
                return Events(documents, styledTags, preserveLifecycleDocs)
            }
        }
    }

    /** Every document the journal or the index knows. */
    data object WholeDb : Journal
}

/**
 * Projects a folded journal into the Room index: the rows, tags, fields and FTS metadata of the
 * documents a [FoldScope] names. It writes the database only; the path work its rows ask for goes
 * to [documentPaths], which settles it once the transaction around the fold has committed.
 */
internal class FoldProjection(
    private val db: NpnDatabase,
    private val documentPaths: DocumentPathReconciler,
) {
    private val documentDao get() = db.documentDao()
    private val tagDao get() = db.tagDao()
    private val ftsDao get() = db.ftsDao()
    private val fieldDao get() = db.documentFieldDao()
    private val fieldDefDao get() = db.fieldDefDao()

    /** Apply the folded effective views of the documents [scope] names into Room in one pass. */
    suspend fun apply(
        state: FoldedState,
        scope: FoldScope.Journal,
    ) {
        fold(state, scope)
    }

    /** Apply the folded effective views of every document into Room; returns the resurrected ones. */
    suspend fun rescan(
        state: FoldedState,
        scope: FoldScope.FullRescan,
    ): List<Resurrected> = fold(state, scope)

    /**
     * Apply the folded effective views into Room in one pass and return resurrected docs.
     *
     * Design notes:
     * - Tags: every tag with a known style gets a [TagEntity] upsert; every doc's document_tags is
     *   replaced with its own tag set ([FoldedState.effectiveTags]).
     *   Tags appearing only in adds (no explicit style) are upserted with null style so
     *   tag-management counts see them.
     * - Fields: every doc's document_fields is replaced with its field map
     *   ([FoldedState.effectiveFields]); the well-known five are additionally cached on the doc row.
     *   reviewed/ocrEngineOverride come from its system props.
     * - Trash and delete: the trashed and deleted flags, trashedOrigPath and trashedAt come from the
     *   fold's lifecycle state. Resurrection, on a full rescan only: when the journal says trashed
     *   or deleted but the document's own bytes are back at its current or original path, the file
     *   wins (flags and missing cleared) and the document is reported, so the caller can record a
     *   restore; until one is recorded, any other fold hides it again (see [FoldPass.lifecycle]).
     *   A document in the trash that has no row gets one ([trashedRow]).
     * - Relinks: the OLD doc is marked deleted/superseded. The NEW doc shows its own metadata, which
     *   the relink's transaction copied onto it ([FoldedState.relinkOps]); no view reads a predecessor.
     * - FTS: after tags/props, each affected doc's metadata-derived fts columns (fileName, tags,
     *   fields) are written. The extracted-text content column belongs to the OCR pipeline, so
     *   the fold never touches it. A brand-new doc gets a row with no content yet, which the OCR
     *   pipeline fills through its own write.
     */
    private suspend fun fold(
        state: FoldedState,
        scope: FoldScope,
    ): List<Resurrected> {
        // Every doc the fold must project: those the events touched, or for a full fold, those the
        // journal has data about plus every doc in the DB, so stale trash/delete flags get cleared.
        val journalDocs = LinkedHashSet<String>()
        if (scope is FoldScope.Events) {
            journalDocs.addAll(scope.docs)
        } else {
            journalDocs.addAll(state.docsWithMetadata())
            // A document in the trash has a row whether or not a rescan ever listed its file.
            journalDocs.addAll(state.trashedDocs())
            for (pm in documentDao.allPathsAndMeta()) journalDocs.add(pm.docId)
            // A deleted row is in neither list above, yet a rescan must project it too: when its
            // own bytes are back on disk (contentAt knows them), the fold resurrects the document,
            // preferring the file over the journal that hides it (see FoldPass.lifecycle).
            (scope as? FoldScope.FullRescan)?.contentAt?.values?.let(journalDocs::addAll)
        }

        // Bulk preload the current projection (rows, tags, fields, FTS content) so the per-doc
        // pass below issues no serial reads; diffed writes are collected and flushed once.
        val preloaded = preloadRows(journalDocs)
        upsertReferencedTags(state, journalDocs, (scope as? FoldScope.Events)?.styledTags.orEmpty())
        // The registry is library-wide and small; a diffed replace per fold keeps it in step
        // with the fold at negligible cost and needs no per-op scoping.
        fieldDefDao.replaceAll(state.effectiveFieldDefs())
        val changes = ProjectionChanges()
        val work = documentPaths.work(state)

        val pass = FoldPass(state, scope, preloaded, changes, work)
        for (doc in journalDocs) {
            val row = preloaded.rows[doc] ?: trashedRow(state, doc) ?: continue
            pass.project(doc, row)
        }
        flushChanges(changes)
        // Tags live only through use: once no live document references a name, drop its row so
        // stale tags never linger in tag lists (see TagDao.pruneUnreferenced).
        tagDao.pruneUnreferenced()
        return pass.resurrected
    }

    /**
     * The row a document in the trash ([FoldedState.trashedDocs]) gets when the index has none. Rows
     * otherwise come only from the files a rescan lists, which never include the trash, so a rebuilt
     * index, or a device that never saw the file before another one trashed it, would show an empty
     * Trash for documents the journal and the trash folder hold. It waits at its sentinel, as a
     * hidden row does, named after the path it was trashed from (or after its id, when that was no
     * document path: a missing row's sentinel), with no stat: no file is known to hold its bytes
     * until a restore moves the trash entry that does (and records that entry's stat).
     */
    private fun trashedRow(
        state: FoldedState,
        doc: String,
    ): DocumentEntity? {
        if (!state.isTrashed(doc) || state.resolveDocId(doc) != doc) return null
        val origPath = state.trashOrigPath(doc) ?: return null
        return DocumentEntity(
            docId = doc,
            relPath = missingSentinel(doc),
            fileName =
                NpnPaths.fileName(origPath).takeIf { NpnPaths.isDocumentPath(origPath) }
                    ?: "${doc.take(TRASHED_NAME_ID_CHARS)}.pdf",
            size = 0,
            mtime = 0,
            addedAt = state.trashedAtMillis(doc) ?: 0,
        )
    }

    /**
     * The current DB projection for one [apply] pass, read in a handful of bulk queries so
     * the per-doc projection performs no serial reads.
     */
    private class PreloadedRows(
        val rows: Map<String, DocumentEntity>,
        val tags: Map<String, List<String>>,
        val fields: Map<String, List<DocumentFieldEntity>>,
        /**
         * Docs that already have an FTS row. Presence only, never the text: the projection just
         * needs to know whether a doc has been indexed yet.
         */
        val ftsIndexed: Set<String>,
    )

    /**
     * Diffed writes accumulated by one [apply] projection pass and flushed once at the end.
     * Each table is written with one bulk call; unchanged tables are left untouched so Room
     * observers only fire on real changes.
     */
    private class ProjectionChanges {
        val docRows = ArrayList<DocumentEntity>()
        val tags = LinkedHashMap<String, List<String>>()
        val fields = LinkedHashMap<String, List<DocumentFieldEntity>>()
        val ftsRows = ArrayList<FtsDocumentEntity>()
    }

    private suspend fun preloadRows(journalDocs: Set<String>): PreloadedRows {
        val docIds = journalDocs.toList()
        val rows = HashMap<String, DocumentEntity>(docIds.size)
        for (row in documentDao.getByDocIds(docIds)) rows[row.docId] = row

        val tags = HashMap<String, List<String>>(docIds.size)
        for ((docId, tagRows) in tagDao.tagsForDocs(docIds).groupBy { it.docId }) {
            tags[docId] = tagRows.map { it.tag }.sorted()
        }

        val fields = fieldDao.fieldsForDocs(docIds).groupBy { it.docId }

        val ftsIndexed = ftsDao.indexedDocIds(docIds).toHashSet()
        return PreloadedRows(rows, tags, fields, ftsIndexed)
    }

    /** Flush one fold's diffed changes, one bulk call per table. */
    private suspend fun flushChanges(changes: ProjectionChanges) {
        if (changes.docRows.isNotEmpty()) documentDao.upsertAll(changes.docRows)
        if (changes.tags.isNotEmpty()) tagDao.setForDocuments(changes.tags)
        if (changes.fields.isNotEmpty()) fieldDao.replaceForDocs(changes.fields)
        if (changes.ftsRows.isNotEmpty()) ftsDao.upsertMetadata(changes.ftsRows)
    }

    /**
     * Upsert all tags referenced anywhere (with styles when set). Diffed against the current rows
     * so an unchanged fold does not touch the table: every write here invalidates the `tags` Room
     * flows (tag management screen, tag colors) even when nothing changed.
     */
    private suspend fun upsertReferencedTags(
        state: FoldedState,
        journalDocs: Set<String>,
        styledTags: Set<String>,
    ) {
        val allTags = LinkedHashSet<String>()
        for (doc in journalDocs) allTags.addAll(state.effectiveTags(doc))
        allTags.addAll(styledTags)
        val existingTagRows = tagDao.all().associateBy { it.name }
        val changed =
            allTags.mapNotNull { tag ->
                val style = state.tagStyle(tag)
                TagEntity(name = tag, color = style?.color, icon = style?.icon).takeIf { existingTagRows[tag] != it }
            }
        if (changed.isNotEmpty()) tagDao.upsertAll(changed)
    }

    /** The trash/delete lifecycle for a doc, after applying the file-resurrection preference. */
    private class Lifecycle(
        val trashed: Boolean,
        val deleted: Boolean,
        val missing: Boolean,
        val relPath: String,
        val fileName: String,
        val origPath: String?,
    )

    /**
     * One [apply] pass over its documents: what it projects them from (the folded [state], the
     * [preloaded] rows) and where it leaves what it projected: [changes] for the database, [work] for
     * the path work after the transaction, [resurrected] for the caller.
     */
    private inner class FoldPass(
        private val state: FoldedState,
        private val scope: FoldScope,
        private val preloaded: PreloadedRows,
        private val changes: ProjectionChanges,
        private val work: DocumentPathReconciler.PathWork,
    ) {
        val resurrected = ArrayList<Resurrected>()

        /** Which document's row holds each path, as the pass found them; a resurrection checks it. */
        private val holders by lazy { preloaded.rows.values.associate { it.relPath to it.docId } }

        /**
         * Project one doc's tags, fields, props, lifecycle, row and FTS from the folded state. The
         * row goes to [work] as written, for the path work after the transaction: the fold itself
         * never moves a file.
         */
        fun project(
            doc: String,
            row: DocumentEntity,
        ) {
            val effTags = tags(doc)
            val fields = fields(doc)
            val lifecycle = lifecycle(doc, row)

            // Diffed: skip the upsert when the effective row equals what is stored, so a fold
            // triggered by one edit does not rewrite (and invalidate) every other doc's row.
            val projectedRow =
                row.copy(
                    relPath = lifecycle.relPath,
                    fileName = lifecycle.fileName,
                    reviewed = state.effectiveProp(doc, SystemProperty.REVIEWED) == "true",
                    // Read cache of the well-known field rows; see DocumentEntity.correspondent.
                    correspondent = fields[FieldKeys.CORRESPONDENT],
                    docType = fields[FieldKeys.DOC_TYPE],
                    docDate = fields[FieldKeys.DOC_DATE],
                    title = fields[FieldKeys.TITLE],
                    notes = fields[FieldKeys.NOTES],
                    ocrEngineOverride = state.effectiveProp(doc, SystemProperty.OCR_ENGINE),
                    trashed = lifecycle.trashed,
                    trashedOrigPath = if (lifecycle.trashed) lifecycle.origPath else null,
                    trashedAt = if (lifecycle.trashed) state.trashedAtMillis(doc) ?: row.trashedAt else null,
                    deleted = lifecycle.deleted,
                    missing = lifecycle.missing,
                )
            // A row the pass made (trashedRow) is new however it compares.
            val rowChanged = projectedRow != row || doc !in preloaded.rows
            if (rowChanged) changes.docRows.add(projectedRow)
            work.rows[doc] = projectedRow

            // FTS: the fold writes only the metadata-derived columns; the extracted text is the OCR
            // pipeline's (FtsDao.upsertMetadata leaves it alone). A brand-new doc has no fts row
            // yet, so it must be written even when nothing else changed; the metadata columns derive
            // from the doc row, tags and fields, so when none changed and a row exists there is
            // nothing to write.
            val ftsRow = FtsRows.row(doc, projectedRow.fileName, effTags, fields, content = null)
            val searchableChanged = rowChanged || doc in changes.tags || doc in changes.fields
            if (searchableChanged || doc !in preloaded.ftsIndexed) changes.ftsRows.add(ftsRow)
            if (documentPaths.pending(state, projectedRow)) work.searchable[doc] = ftsRow
        }

        /**
         * A doc's tags, sorted. Diffed against the preloaded rows: a
         * rewrite goes to [changes] only when the set actually changed, so an unrelated edit does not
         * invalidate `document_tags` observers for every doc.
         */
        private fun tags(doc: String): List<String> {
            val effTags = state.effectiveTags(doc).sorted()
            if (preloaded.tags[doc].orEmpty() != effTags) changes.tags[doc] = effTags
            return effTags
        }

        /**
         * Every field value for a doc ([FoldedState.effectiveFields]).
         * Rows carry the numeric shadow for the registry's NUMBER/DECIMAL fields. Diffed like tags so
         * an unrelated edit does not rewrite this doc's field rows. Returns the effective map, which
         * also feeds the cached well-known columns and the FTS row.
         */
        private fun fields(doc: String): Map<String, String> {
            val effFields = state.effectiveFields(doc)
            val rows =
                effFields.map { (key, value) ->
                    val numValue = FieldValues.numeric(state.effectiveFieldType(key), value)
                    DocumentFieldEntity(doc, key, value, numValue = numValue)
                }
            if (preloaded.fields[doc].orEmpty().sortedBy { it.fieldKey } != rows.sortedBy { it.fieldKey }) {
                changes.fields[doc] = rows
            }
            return effFields
        }

        /**
         * Resolve the doc's trash/delete state. On a [FoldScope.FullRescan] the file is preferred:
         * if the journal says trashed/deleted but the document's own bytes are back ([returnedPath]),
         * the flags are cleared and a [Resurrected] recorded. Docs an [FoldScope.Events] fold
         * preserves keep their stored lifecycle.
         */
        private fun lifecycle(
            doc: String,
            row: DocumentEntity,
        ): Lifecycle {
            // Relinked onto another id: deleted for good, on every fold, whatever its own events say.
            val superseded = state.resolveDocId(doc) != doc
            val journalDeleted = state.isDeleted(doc) || superseded
            val journalTrashed = state.isTrashed(doc)
            val origPath = state.trashOrigPath(doc)
            val preserved = scope is FoldScope.Events && doc in scope.preserveLifecycleDocs

            var trashed = if (preserved) row.trashed else journalTrashed
            var deleted = if (preserved) row.deleted || superseded else journalDeleted
            var missing = row.missing
            var relPath = row.relPath
            var fileName = row.fileName

            val hidden = journalTrashed || journalDeleted
            val backAt =
                (scope as? FoldScope.FullRescan)
                    ?.takeIf { hidden && !superseded }
                    ?.let { returnedPath(doc, row, origPath, it.contentAt) }
            if (backAt != null) {
                // Resurrection: prefer the file. Clear trashed/deleted for the working view.
                trashed = false
                deleted = false
                missing = false
                if (backAt != relPath) {
                    relPath = backAt
                    fileName = NpnPaths.fileName(backAt)
                }
                val hiddenAt = state.hiddenBy(doc)?.physicalMillis ?: 0L
                resurrected.add(Resurrected(doc, relPath, hiddenAtMillis = hiddenAt))
            }
            // A row waiting at its sentinel has no file. Shown again (another device restored the
            // document and its file has not arrived), it is missing until a rescan finds the file.
            if (!trashed && !deleted && relPath == missingSentinel(doc)) missing = true
            return Lifecycle(trashed, deleted, missing, relPath, fileName, origPath)
        }

        /**
         * Where a hidden [doc]'s own bytes are back in the library: at its row's path, else at
         * [origPath], the path it was trashed from, when no other row holds that. Only a file whose
         * hash the rescan knows ([contentAt]) is evidence: another document's file at the path, or
         * one still being written, says nothing about this one.
         */
        private fun returnedPath(
            doc: String,
            row: DocumentEntity,
            origPath: String?,
            contentAt: Map<String, String>,
        ): String? =
            row.relPath.takeIf { contentAt[it] == doc }
                ?: origPath?.takeIf { contentAt[it] == doc && it !in holders }
    }
}

/** How much of its id names a trashed document whose trash recorded no document path. */
private const val TRASHED_NAME_ID_CHARS = 12
