package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.db.AiEditJobDocEntity
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.TempIndexDb
import dev.npn.core.index.MutableClock
import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.FakeAiSuggest
import dev.npn.core.testing.FakeDocContentFacade
import dev.npn.core.testing.FakeDocumentRepository
import dev.npn.core.testing.FakeFieldRegistry
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.FakeTagRepository
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import dev.npn.core.work.EndpointHealth

/**
 * Real device database (the queue owns job and document rows) + the shared AI-edit fakes for the
 * model, the writes and the document text.
 */
internal class AiEditQueueRig(
    private val dbs: TempIndexDb,
) : AutoCloseable {
    val db: DeviceDatabase = dbs.deviceDb
    val documents = FakeDocumentRepository()
    val meta = FakeMetadataWriter(batchedFieldsOnly = true)
    val content = FakeDocContentFacade()
    val ai = FakeAiSuggest()
    val fieldRegistry = FakeFieldRegistry()

    /** The library's vocabulary: the tags these tests suggest as ones it already has. */
    val tags = FakeTagRepository(listOf("t", "invoice"))
    val clock = MutableClock(start = 1_000L)
    var config: AiConfig = ACTIVE
    private var ids = 0

    /** How often a queue asked for its drain; the session's drainer would run once per kick. */
    var kicks = 0

    /** How many writes a queue handed to the session as owned work, as it must every write a caller drives. */
    var ownedWrites = 0

    /** Thrown by every owned write when set, as a closed device database would. */
    var ownWriteFailure: Throwable? = null

    /** The endpoint's cooldown, as the session's AI facade holds it for every queue. */
    val health = EndpointHealth()

    val queue: AiEditQueue by lazy { newQueue(health) }

    /**
     * A fresh queue over the same database, which is also how "the app was restarted" is
     * expressed: nothing but the job rows carries over, not even the endpoint's cooldown.
     */
    fun newQueue(health: EndpointHealth = EndpointHealth()) =
        AiEditQueue(
            dao = db.aiEditJobDao(),
            documents = documents,
            content = content,
            meta = meta,
            ai = ai,
            fieldRegistry = fieldRegistry,
            tags = tags,
            readConfig = { config },
            ledger = dev.npn.core.ai.AiLedger.None,
            clock = clock,
            health = health,
            onEnqueued = { kicks++ },
            ownWrite = { write ->
                ownedWrites++
                ownWriteFailure?.let { throw it }
                write()
            },
            newJobId = { "job-${++ids}" },
        )

    /** Put [docIds] in the library and queue a run over them. */
    suspend fun enqueue(
        vararg docIds: String,
        request: AiEditRequest = AiEditRequest(),
    ): String {
        documents.documents.value = docIds.map { listItem(it) }
        return queue.enqueueEditRun(docIds.toList(), request)
    }

    suspend fun rows(jobId: String): List<AiEditJobDocEntity> = db.aiEditJobDao().docsFor(jobId)

    suspend fun job(jobId: String) = db.aiEditJobDao().job(jobId)

    /**
     * Drain to a standstill, waiting out every cooldown and backoff on the way, which is what the
     * app's kicks amount to over a long enough stretch of wall clock.
     */
    suspend fun drainUntilSettled() {
        repeat(DRAIN_ROUNDS) {
            clock.advance(OUTAGE_STEP_MS)
            while (queue.processNext()) {
                // keep going while the drain can make progress
            }
        }
    }

    override fun close() = dbs.close()
}

/** Longer than the backoff ceiling and the endpoint cooldown, so one step clears both. */
internal const val OUTAGE_STEP_MS = 25L * 60L * 60L * 1000L

/** Enough rounds to burn the systemic budget and then the per-document attempt cap. */
private const val DRAIN_ROUNDS = AiEditQueue.MAX_JOB_ATTEMPTS + AiEditQueue.MAX_DOC_ATTEMPTS + 2

internal suspend fun withQueue(block: suspend (AiEditQueueRig) -> Unit) {
    AiEditQueueRig(TempIndexDb("npn-edit-queue-db")).use { block(it) }
}

internal fun suggestion(
    tag: String = "t",
    title: String? = null,
) = edit(
    addTags = listOf(TagSuggestion(tag, isNew = false)),
    metadata = FieldSuggestion(title?.let { mapOf(FieldKeys.TITLE to it) }.orEmpty()),
)
