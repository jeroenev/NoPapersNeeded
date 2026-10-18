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
    /** Each call owned by the session's lifetime, as every facet's is. */
    val writer: MetadataWriterImpl,
    val lifecycle: DocLifecycleImpl,
    val reviewConfig: ReviewConfigFacade,
    val trashRetention: JournalTrashRetention,
    /** The trash auto-purge by [trashRetention]. */
    val trashPurge: TrashPurge,
    val content: DocContentFacade,
    val imports: ImportService,
)
