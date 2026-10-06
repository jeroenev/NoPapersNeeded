package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.UnattendedAiPolicy
import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.AiEnrichedEntity
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.TempIndexDb
import dev.npn.core.db.documentEntity
import dev.npn.core.index.MutableClock
import dev.npn.core.testing.FakeAiSuggest
import dev.npn.core.testing.FakeFieldRegistry
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.work.EndpointHealth
import dev.npn.core.work.QueueDrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope

// Shared fixtures for the AiAutoEnricher suites: AiAutoEnricherTest covers what a pass writes,
// AiAutoEnricherRetryTest covers when it runs again.

/** A configured endpoint with the automation on, i.e. the only state that does any work. */
internal val ACTIVE =
    AiConfig(
        enabled = true,
        provider = AiProvider.CUSTOM,
        baseUrl = "http://localhost:1234",
        model = "test-model",
        autoOnOcr = true,
    )

/**
 * Real Room index and device database (the enricher reads documents from one and owns queue rows
 * and stamps in the other) + fakes for AI and writes.
 */
internal class Rig(
    private val dbs: TempIndexDb,
    private val scope: CoroutineScope,
) : AutoCloseable {
    val db: NpnDatabase = dbs.db
    val deviceDb: DeviceDatabase = dbs.deviceDb
    val ai = FakeAiSuggest()
    val meta = FakeMetadataWriter(batchedFieldsOnly = true)
    val fieldRegistry = FakeFieldRegistry()
    val clock = MutableClock(start = 5_000L)
    var config: AiConfig = ACTIVE

    /** How often the enricher asked for its drain; the session's drainer would run once per kick. */
    var kicks = 0

    /** The endpoint's cooldown, as the session's AI facade holds it for every queue. */
    val health = EndpointHealth()

    /** Whether the library refuses metadata writes, as a latched journal coordinator does. */
    var writesBlocked = false

    val enricher: AiAutoEnricher by lazy { newEnricher(health) }

    /**
     * A fresh enricher over the same database, which is also how "the app was restarted" is
     * expressed: nothing but the queue rows carries over, not even the endpoint's cooldown.
     */
    fun newEnricher(health: EndpointHealth = EndpointHealth()) =
        AiAutoEnricher(
            documentDao = db.documentDao(),
            queueDao = deviceDb.aiEnrichQueueDao(),
            ai = ai,
            meta = meta,
            fieldRegistry = fieldRegistry,
            policy = UnattendedAiPolicy { config },
            clock = clock,
            health = health,
            checkWritable = { check(!writesBlocked) { "Metadata writes are disabled" } },
            onEnqueued = { kicks++ },
        )

    /**
     * Drain exactly as the session does: through the shared queue drainer, and by default against
     * the one long-lived [enricher], since a session builds it once and kicks it repeatedly. Pass
     * [newEnricher] to model a restart — the difference is visible, because not all of the
     * enricher's state is in the database.
     */
    suspend fun drain(enricher: AiAutoEnricher = this.enricher): Int =
        QueueDrainer(scope) { enricher.processNext() }.drain()

    /**
     * Insert a document and queue it, one clock tick apart so the queue order (by
     * [AiEnrichQueueEntity.nextAttemptAt]) is deterministic in tests that assert on it.
     */
    suspend fun insertAndQueue(vararg docIds: String) {
        docIds.forEach {
            insert(it)
            enricher.enqueue(it)
            clock.advance(1L)
        }
    }

    suspend fun insert(
        docId: String,
        trashed: Boolean = false,
        aiEnrichedAt: Long? = null,
    ) {
        db.documentDao().upsert(documentEntity(docId, trashed = trashed))
        aiEnrichedAt?.let { deviceDb.aiEnrichQueueDao().markEnriched(AiEnrichedEntity(docId, it)) }
    }

    suspend fun trash(docId: String) {
        val doc = db.documentDao().getByDocId(docId)!!
        db.documentDao().upsert(doc.copy(trashed = true))
    }

    suspend fun enrichedAt(docId: String): Long? = deviceDb.aiEnrichQueueDao().enrichedAt(docId)

    suspend fun row(docId: String): AiEnrichQueueEntity? = deviceDb.aiEnrichQueueDao().getByDocId(docId)

    override fun close() = dbs.close()
}

internal suspend fun TestScope.withRig(block: suspend (Rig) -> Unit) {
    Rig(TempIndexDb("npn-enrich-db"), backgroundScope).use { block(it) }
}
