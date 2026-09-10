package dev.npn.app

import dev.npn.core.app.LibrarySession
import kotlinx.coroutines.CoroutineScope

/**
 * Convenience composer of the per-facet fakes, mirroring how [dev.npn.core.app.LibrarySessionImpl]
 * assembles the real ones. Tests that drive a viewmodel through its narrow constructor should
 * construct only the facet fakes they need; this aggregate exists for tests that would otherwise
 * list half a dozen of them.
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
    override val maintenance = FakeMaintenanceFacade()
    override val work = FakeWorkFacade()
    override val aiEditQueue = FakeAiEditQueue()

    override fun start(scope: CoroutineScope) = Unit

    override suspend fun close() = Unit
}
