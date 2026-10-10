package dev.npn.core.app

/**
 * The session's three write facets: every call on [writer] runs whole as work the session owns
 * ([SessionLifetime.owned]), its reads and file checks outside the writer lock and its follow-ups
 * (storage-path rules, auto-match) included, so closing the session cancels and waits for it before
 * the host closes the databases. Work that already runs as the session's (the queue drains, an owned
 * split or OCR request) calls [writer] itself.
 */
internal class OwnedWriter(
    private val writer: MetadataWriterImpl,
    private val lifetime: SessionLifetime,
) : MetadataWriter,
    TagAdmin,
    DocLifecycle {
    private suspend fun <T> owned(block: suspend () -> T): T = lifetime.owned(block)

    override suspend fun addTag(
        docIds: List<String>,
        tag: String,
    ) = owned { writer.addTag(docIds, tag) }

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) = owned { writer.removeTag(docIds, tag) }

    override suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    ) = owned { writer.setReviewed(docIds, reviewed) }

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) = owned { writer.setField(docId, key, value) }

    override suspend fun setField(
        docIds: List<String>,
        key: String,
        value: String?,
    ) = owned { writer.setField(docIds, key, value) }

    override suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String>,
        fields: Map<String, String?>,
        fillOnly: Boolean,
    ) = owned { writer.applyEdits(docIds, addTags, fields, fillOnly) }

    override suspend fun renameTag(
        from: String,
        to: String,
    ) = owned { writer.renameTag(from, to) }

    override suspend fun mergeTag(
        from: String,
        to: String,
    ) = owned { writer.mergeTag(from, to) }

    override suspend fun deleteTag(name: String) = owned { writer.deleteTag(name) }

    override suspend fun setTagStyle(
        tag: String,
        color: String?,
        icon: String?,
    ) = owned { writer.setTagStyle(tag, color, icon) }

    override suspend fun trashDoc(docId: String): TrashReceipt? = owned { writer.trashDoc(docId) }

    override suspend fun restoreDoc(docId: String) = owned { writer.restoreDoc(docId) }

    override suspend fun restoreDocs(docIds: List<String>): Int = owned { writer.restoreDocs(docIds) }

    override suspend fun undoTrash(trashes: List<TrashReceipt>): Int = owned { writer.undoTrash(trashes) }

    override suspend fun deleteDocPermanently(docId: String): PermanentDeleteOutcome =
        owned { writer.deleteDocPermanently(docId) }

    override suspend fun deleteDocsPermanently(docIds: List<String>): PermanentDeleteTally =
        owned { writer.deleteDocsPermanently(docIds) }

    override suspend fun renameFile(
        docId: String,
        newFileName: String,
    ) = owned { writer.renameFile(docId, newFileName) }

    override suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    ) = owned { writer.moveFile(docId, newDirRelPath) }
}
