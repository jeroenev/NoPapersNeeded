package dev.npn.core.app

import dev.npn.core.ai.AiChat
import dev.npn.core.ai.AiSuggest
import kotlinx.coroutines.CoroutineScope

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
    val maintenance: MaintenanceFacade
    val work: WorkFacade
    val aiEditQueue: AiEditQueueFacade

    /** Start folder watching (desktop), OCR worker, periodic reconciliation. Idempotent. */
    fun start(scope: CoroutineScope)

    /** Stop and await all work owned by this session. Does not close environment-owned resources. */
    suspend fun close()
}
