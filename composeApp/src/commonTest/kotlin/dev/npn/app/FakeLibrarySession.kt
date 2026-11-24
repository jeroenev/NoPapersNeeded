package dev.npn.app

import dev.npn.core.app.LibrarySession
import dev.npn.core.index.WriteBlock
import dev.npn.core.testing.FakeAiSuggest
import dev.npn.core.testing.FakeDocContentFacade
import dev.npn.core.testing.FakeDocumentRepository
import dev.npn.core.testing.FakeFieldRegistry
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.FakeTagRepository
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The per-facet fakes composed into a session, mirroring how core's own session assembles the real
 * ones: every viewmodel takes the session and reads the facets it needs.
 *
 * Each property keeps its concrete fake type so tests can reach the recorded calls and injection
 * hooks (e.g. `session.docLifecycle.trashedDocs`, `session.ocr.failReOcr`).
 */
class FakeLibrarySession : LibrarySession {
    override val documents = FakeDocumentRepository()
    override val tags = FakeTagRepository()
    override val savedViews = FakeSavedViewRepository()
    override val rules = FakeAutoMatchRuleRepository()
    override val fieldRegistry = FakeFieldRegistry()
    override val meta = FakeMetadataWriter()
    override val tagAdmin = FakeTagAdmin()
    override val docLifecycle = FakeDocLifecycle()
    override val content = FakeDocContentFacade()
    override val aiChat = FakeAiChat()
    override val aiSuggest = FakeAiSuggest()
    override val ingest = FakeIngestFacade()
    override val ocr = FakeOcrFacade()
    override val reviewConfig = FakeReviewConfigFacade()
    override val storagePath = FakeStoragePathFacade()
    override val trashRetention = FakeTrashRetention()
    override val reminders = FakeRemindersFacade()
    override val maintenance = FakeMaintenanceFacade()
    override val work = FakeWorkFacade()
    override val aiEditQueue = FakeAiEditQueue()
    override val sync = FakeSyncFacade()
    override val mail = FakeMailFacade()
    override var log: NpnLog = NpnLog.None

    /** Why writes are refused; a test sets it to show the library read-only. */
    override val writeBlock = MutableStateFlow<WriteBlock?>(null)

    override fun start() = Unit

    var initialRescanSucceeded = true

    override suspend fun awaitInitialRescan() = initialRescanSucceeded

    override suspend fun close() = Unit
}
