package dev.npn.core.app

import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatTurn
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiFacade
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.MetadataSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.index.MutableClock
import dev.npn.core.model.DocProperty
import dev.npn.core.work.QueueDrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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

internal class StubAi : AiFacade {
    var suggestion: AiEditSuggestion = AiEditSuggestion.EMPTY

    /** Called with the docId; throw to simulate an endpoint or model failure for that document. */
    var failWith: ((String) -> Unit)? = null
    val calls = mutableListOf<String>()
    val requests = mutableListOf<AiEditRequest>()

    override suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
    ): AiEditSuggestion {
        calls += docId
        requests += request
        failWith?.invoke(docId)
        return suggestion
    }

    override suspend fun config(): AiConfig = error("unused")

    override fun ask(
        history: List<AiChatTurn>,
        question: String,
    ): Flow<AiChatEvent> = error("unused")
}

/**
 * Records the batched writes. Every setter errors: the enricher must write through
 * [MetadataWriter.applyEdits] so a document is renamed at most once per pass, and a test that
 * silently accepted setter calls would not notice it regressing to one write per field.
 */
internal class RecordingMeta : MetadataWriter {
    val edits = mutableListOf<Edit>()

    val addedTags get() = edits.map { it.docId to it.addTags }
    val titles get() = edits.mapNotNull { e -> e.properties[DocProperty.TITLE]?.let { e.docId to it } }
    val correspondents get() = edits.mapNotNull { e -> e.properties[DocProperty.CORRESPONDENT]?.let { e.docId to it } }
    val docDates get() = edits.mapNotNull { e -> e.properties[DocProperty.DOC_DATE]?.let { e.docId to it } }

    class Edit(
        val docId: String,
        val addTags: List<String>,
        val properties: Map<DocProperty, String?>,
    )

    override suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String>,
        properties: Map<DocProperty, String?>,
    ) {
        // The enricher works a document at a time; a multi-doc call here would mean it had started
        // batching unrelated documents into one transaction.
        edits += Edit(docIds.single(), addTags, properties)
    }

    override suspend fun addTag(
        docIds: List<String>,
        tag: String,
    ) = error("must batch through applyEdits")

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) = error("removals are never requested by the automatic pass")

    override suspend fun setTitle(
        docId: String,
        title: String?,
    ) = error("must batch through applyEdits")

    override suspend fun setCorrespondent(
        docIds: List<String>,
        value: String?,
    ) = error("must batch through applyEdits")

    override suspend fun setDocDate(
        docId: String,
        isoDate: String?,
    ) = error("must batch through applyEdits")

    override suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    ) = error("unused")

    override suspend fun setDocType(
        docIds: List<String>,
        value: String?,
    ) = error("unused")

    override suspend fun setNotes(
        docId: String,
        notes: String?,
    ) = error("unused")

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) = error("unused")
}

/** Real Room index (the enricher owns queue rows and document stamps) + fakes for AI and writes. */
internal class Rig(
    val db: NpnDatabase,
    val dbDir: File,
    private val scope: CoroutineScope,
) {
    val ai = StubAi()
    val meta = RecordingMeta()
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
            queueDao = db.aiEnrichQueueDao(),
            ai = ai,
            meta = meta,
            readConfig = { config },
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
    ) = db.documentDao().upsert(
        DocumentEntity(
            docId = docId,
            relPath = "$docId.pdf",
            fileName = "$docId.pdf",
            size = 0L,
            mtime = 0L,
            addedAt = 0L,
            trashed = trashed,
            aiEnrichedAt = aiEnrichedAt,
        ),
    )

    suspend fun trash(docId: String) {
        val doc = db.documentDao().getByDocId(docId)!!
        db.documentDao().upsert(doc.copy(trashed = true))
    }

    suspend fun enrichedAt(docId: String): Long? = db.documentDao().getByDocId(docId)?.aiEnrichedAt

    suspend fun row(docId: String): AiEnrichQueueEntity? = db.aiEnrichQueueDao().getByDocId(docId)

    fun close() {
        db.close()
        dbDir.deleteRecursively()
    }
}

internal suspend fun TestScope.withRig(block: suspend (Rig) -> Unit) {
    val dbDir = Files.createTempDirectory("npn-enrich-db").toFile()
    val rig = Rig(npnDatabase(File(dbDir, "index.db")), dbDir, backgroundScope)
    try {
        block(rig)
    } finally {
        rig.close()
    }
}
