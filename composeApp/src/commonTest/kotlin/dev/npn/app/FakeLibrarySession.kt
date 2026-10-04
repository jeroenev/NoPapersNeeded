package dev.npn.app

import dev.npn.core.app.LibrarySession

/**
 * The per-facet fakes composed into a session, mirroring how [dev.npn.core.app.LibrarySessionImpl]
 * assembles the real ones: every viewmodel takes the session and reads the facets it needs.
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
    override val maintenance = FakeMaintenanceFacade()
    override val work = FakeWorkFacade()
    override val aiEditQueue = FakeAiEditQueue()

    override fun start() = Unit

    override suspend fun close() = Unit
}
