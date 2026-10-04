package dev.npn.core.app

import dev.npn.core.ai.AiChat
import dev.npn.core.ai.AiSuggest

/**
 * Everything scoped to one opened library folder: a pure aggregate of narrow facets plus
 * lifecycle. Consumers should depend on the facets they use, never on this aggregate; only the
 * composable tree and platform entry points hold the whole session.
 */
interface LibrarySession {
    val documents: DocumentRepository
    val tags: TagRepository
    val savedViews: SavedViewRepository
    val rules: AutoMatchRuleRepository
    val fieldRegistry: FieldRegistry
    val meta: MetadataWriter
    val tagAdmin: TagAdmin
    val docLifecycle: DocLifecycle
    val content: DocContentFacade
    val aiChat: AiChat
    val aiSuggest: AiSuggest
    val ingest: IngestFacade
    val ocr: OcrFacade
    val reviewConfig: ReviewConfigFacade
    val storagePath: StoragePathFacade
    val maintenance: MaintenanceFacade
    val work: WorkFacade
    val aiEditQueue: AiEditQueueFacade

    /**
     * Start folder watching, the queue drains and periodic reconciliation, all on the scope the
     * session was opened with. Idempotent; fails once the session is closed.
     */
    fun start()

    /**
     * Stop and await all work owned by this session. Final: the session cannot be started again,
     * and its operations fail from then on. Does not close environment-owned resources.
     */
    suspend fun close()
}
