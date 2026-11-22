package dev.npn.core.app

import dev.npn.core.ai.AiChat
import dev.npn.core.ai.AiSuggest
import dev.npn.core.app.aiedit.AiEditQueueFacade
import dev.npn.core.index.WriteBlock
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything scoped to one opened library folder: a pure aggregate of narrow facets plus
 * lifecycle. Viewmodels take the session and read the facets they use; code in `core` depends on
 * the facets alone.
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
    val trashRetention: TrashRetentionFacade
    val maintenance: MaintenanceFacade
    val work: WorkFacade
    val aiEditQueue: AiEditQueueFacade
    val aiSpend: AiSpendFacade
    val sync: SyncFacade

    /** Where the session's failures go, for a caller reporting one of its own that nobody awaits. */
    val log: NpnLog

    /**
     * Why the library refuses writes now, null while it takes them. Library-wide, so it is the
     * session's rather than any one facet's: every facet that writes is refused alike, with a
     * [dev.npn.core.index.MetadataWriteBlocked] carrying this reason. Set from the open on when the
     * journal does not fold whole (the library opens read-only) and after any pass that fails, and
     * cleared by the next pass that reads the journal whole, such as a rescan.
     */
    val writeBlock: StateFlow<WriteBlock?>

    /**
     * Start folder watching, the queue drains and periodic reconciliation, all on the scope the
     * session was opened with. Idempotent; fails once the session is closed.
     */
    fun start()

    /**
     * Wait until the initial rescan [start] launched has ended, however it ended, and say whether
     * the queue drains may run: false when it failed or the session closed first, which leaves the
     * drains waiting for a later rescan to succeed. Call only after [start].
     */
    suspend fun awaitInitialRescan(): Boolean

    /**
     * Stop and await all work owned by this session. Final: the session cannot be started again,
     * and its operations fail from then on. Does not close environment-owned resources.
     */
    suspend fun close()
}
