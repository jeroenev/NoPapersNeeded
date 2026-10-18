package dev.npn.core.app

import dev.npn.core.index.ImportService

/**
 * The library's metadata layer over its journal: the repositories and writers the session hands
 * out as facets, the auto-match rules OCR runs, the trash retention and its purge, and the import
 * path.
 */
internal class Library(
    val documents: DocumentRepository,
    val tags: TagRepository,
    val savedViews: SavedViewRepository,
    val fieldRegistry: FieldRegistry,
    val storagePath: StoragePathFacade,
    /** With the rules' read side and the applier OCR runs them through. */
    val rules: AutoMatchRuleRepositoryImpl,
    /** [writer] and [lifecycle] for a caller outside the session: each call owned by its lifetime. */
    val ownedWriter: OwnedWriter,
    val reviewConfig: ReviewConfigFacade,
    val trashRetention: JournalTrashRetention,
    /** The trash auto-purge by [trashRetention]. */
    val trashPurge: TrashPurge,
    val content: DocContentFacade,
    val imports: ImportService,
) {
    /** The writer itself, for work that already runs as the session's: the drains, owned facet calls. */
    val writer: MetadataWriterImpl get() = ownedWriter.writer

    /** The document lifecycle itself, for work that already runs as the session's (a split's trash). */
    val lifecycle: DocLifecycleImpl get() = ownedWriter.lifecycle
}
