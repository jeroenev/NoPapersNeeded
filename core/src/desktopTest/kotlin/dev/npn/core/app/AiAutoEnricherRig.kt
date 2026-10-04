package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.FakeAiSuggest
import dev.npn.core.ai.UnattendedAiPolicy
import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.AiEnrichedEntity
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.deviceDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.index.MutableClock
import dev.npn.core.model.FieldKeys
import dev.npn.core.work.QueueDrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import java.io.File
import java.nio.file.Files

// Shared fixtures for the AiAutoEnricher suites: AiAutoEnricherTest covers what a pass writes,
// AiAutoEnricherRetryTest covers when it runs again.
//
// Narrow fakes: only what AiAutoEnricher touches is wired; the rest throws.

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
 * Records the batched writes. Every setter errors: the enricher must write through
 * [MetadataWriter.applyEdits] so a document is renamed at most once per pass, and a test that
 * silently accepted setter calls would not notice it regressing to one write per field.
 */
internal class RecordingMeta : MetadataWriter {
    val edits = mutableListOf<Edit>()

    /**
     * Every tag written, as (docId, its tags): the enricher writes tags in the SAME atomic
     * `applyEdits` as the fields, so they arrive on the edit, never through [addTag].
     */
    val addedTags get() = edits.map { it.docId to it.addTags }
    val titles get() = edits.mapNotNull { e -> e.fields[FieldKeys.TITLE]?.let { e.docId to it } }
    val correspondents get() = edits.mapNotNull { e -> e.fields[FieldKeys.CORRESPONDENT]?.let { e.docId to it } }
    val docDates get() = edits.mapNotNull { e -> e.fields[FieldKeys.DOC_DATE]?.let { e.docId to it } }

    /**
     * When set, the atomic write (`applyEdits`) throws this instead of recording — so a test can
     * assert the write's own cause is what reaches the queue's backoff, not a synthetic one.
     */
    var failEditsWith: (() -> Throwable)? = null

    class Edit(
        val docId: String,
        val addTags: List<String>,
        val fields: Map<String, String?>,
    )

    override suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String>,
        fields: Map<String, String?>,
    ) {
        failEditsWith?.let { throw it() }
        // The enricher works a document at a time; a multi-doc call here would mean it had started
        // batching unrelated documents into one transaction.
        edits += Edit(docIds.single(), addTags, fields)
    }

    override suspend fun addTag(
        docIds: List<String>,
        tag: String,
    ) = error("the enricher writes tags atomically through applyEdits, not addTag")

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) = error("removals are never requested by the automatic pass")

    override suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    ) = error("unused")

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) = error("must batch through applyEdits")

    override suspend fun setField(
        docIds: List<String>,
        key: String,
        value: String?,
    ) = error("must batch through applyEdits")
}

/**
 * Real Room index and device database (the enricher reads documents from one and owns queue rows
 * and stamps in the other) + fakes for AI and writes.
 */
internal class Rig(
    val db: NpnDatabase,
    val deviceDb: DeviceDatabase,
    val dbDir: File,
    private val scope: CoroutineScope,
) {
    val ai = FakeAiSuggest()
    val meta = RecordingMeta()
    val fieldRegistry = FakeFieldRegistry()
    val clock = MutableClock(start = 5_000L)
    var config: AiConfig = ACTIVE

    val enricher: AiAutoEnricher by lazy { newEnricher() }

    /**
     * A fresh enricher over the same database, which is also how "the app was restarted" is
     * expressed: nothing but the queue rows carries over.
     */
    fun newEnricher() =
        AiAutoEnricher(
            documentDao = db.documentDao(),
            queueDao = deviceDb.aiEnrichQueueDao(),
            ai = ai,
            meta = meta,
            fieldRegistry = fieldRegistry,
            policy = UnattendedAiPolicy.fromConfig { config },
            clock = clock,
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
        db.documentDao().upsert(
            DocumentEntity(
                docId = docId,
                relPath = "$docId.pdf",
                fileName = "$docId.pdf",
                size = 0L,
                mtime = 0L,
                addedAt = 0L,
                trashed = trashed,
            ),
        )
        aiEnrichedAt?.let { deviceDb.aiEnrichQueueDao().markEnriched(AiEnrichedEntity(docId, it)) }
    }

    suspend fun trash(docId: String) {
        val doc = db.documentDao().getByDocId(docId)!!
        db.documentDao().upsert(doc.copy(trashed = true))
    }

    suspend fun enrichedAt(docId: String): Long? = deviceDb.aiEnrichQueueDao().enrichedAt(docId)

    suspend fun row(docId: String): AiEnrichQueueEntity? = deviceDb.aiEnrichQueueDao().getByDocId(docId)

    fun close() {
        db.close()
        deviceDb.close()
        dbDir.deleteRecursively()
    }
}

internal suspend fun TestScope.withRig(block: suspend (Rig) -> Unit) {
    val dbDir = Files.createTempDirectory("npn-enrich-db").toFile()
    val rig =
        Rig(npnDatabase(File(dbDir, "index.db")), deviceDatabase(File(dbDir, "device.db")), dbDir, backgroundScope)
    try {
        block(rig)
    } finally {
        rig.close()
    }
}
