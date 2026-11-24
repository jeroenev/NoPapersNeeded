package dev.npn.core.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

// The entities of DeviceDatabase (device.db), which holds what only this device knows and is never
// dropped: unlike the index's entities in Entities.kt, every change to one here needs a real
// migration and a new DeviceDatabaseSchemaTest entry for the version it ships in.
// KeyValueEntity stays in Entities.kt, as both databases have a `kv` table.

/**
 * Work list for the OCR pipeline, in [DeviceDatabase]. A row means "this document is owed an OCR
 * pass" on this device, and also holds what only this device knows about that pass: a re-run or an
 * AI run asked for here, the attempts and backoff spent, and (in [OcrPageCacheEntity]) the pages
 * already paid for. Unlike
 * [AiEnrichQueueEntity] it does keep terminal states: `done` is the history
 * `OcrPipeline.maybeQueueUpgrade` reads to decide whether a better engine is worth re-queueing,
 * and `failed` is the attempt cap being spent.
 *
 * There is no `running`, for the same reason [AiEnrichQueueEntity] has no state column at all. The
 * drain is single-flight in-process ([dev.npn.core.work.QueueDrainer]), so only the running pass
 * could write `running`, and only that pass surviving to the end could clear it. A killed process
 * or a cancelled drain would leave a row marked running that `OcrQueueDao.nextPending` never
 * returns again: invisible to the drain for good, while it reads as busy to the user.
 *
 * So a row stays `pending` while it is being worked on, and an interrupted pass leaves it exactly
 * as it found it — owed, and taken by the next drain. Who is being worked on right now is
 * in-process knowledge (`OcrPipeline.inFlight`), which cannot outlive the process that knows it.
 * A request recorded while a pass runs outlives that pass too: the pass settles the row only when
 * it saw every request on it (`OcrQueueDao.settle`).
 */
@Entity(tableName = "ocr_queue")
data class OcrQueueEntity(
    @PrimaryKey val docId: String,
    /** A [QueueState.column] — see the class doc for why `running` is not one of them. */
    val state: String,
    val attempts: Int = 0,
    /**
     * When the row was last written. Asking for a run ([rerun], [aiRequested]) always moves it on,
     * so a pass tells a request made while it ran from the one it read, even when the flag was
     * already set (`OcrQueueDao.settle`).
     */
    val updatedAt: Long,
    val lastError: String? = null,
    /**
     * The user asked for this document to be OCR'd again: run this device's engine when it ranks
     * at least as high as the indexed version, not only when it would rank higher.
     */
    @ColumnInfo(defaultValue = "0") val rerun: Boolean = false,
    /**
     * The user asked *on this device* for the AI engine to read this document
     * (`OcrFacade.ocrWithAi`), so its run may spend without the automatic setting and past the page
     * cap. Local on purpose: the engine pin that comes with the request is synced library data, and
     * a pin arriving from another replica must not make this one pay as well.
     */
    @ColumnInfo(defaultValue = "0") val aiRequested: Boolean = false,
    /**
     * Epoch millis before which a failed row must not be retried (`dev.npn.core.work.backoffMs`);
     * 0 for a row that has not failed. A retry that came straight back would pay for the same
     * failing call again, which for an AI run is a whole document of pages.
     */
    @ColumnInfo(defaultValue = "0") val nextAttemptAt: Long = 0,
)

/** [QueueState.DONE]'s stored value, for a query that filters on it. */
internal const val QUEUE_DONE = "done"

/** [QueueState.FAILED]'s stored value, for a query that filters on it. */
internal const val QUEUE_FAILED = "failed"

/** Where an [OcrQueueEntity] row stands; [column] is the spelling in `ocr_queue.state`. */
enum class QueueState(
    val column: String,
) {
    /** Owed a pass, now or once its backoff ([OcrQueueEntity.nextAttemptAt]) has passed. */
    PENDING("pending"),

    /** This device's run is settled; the row stays as history. */
    DONE(QUEUE_DONE),

    /** Given up on after the attempt cap, until an explicit re-run. */
    FAILED(QUEUE_FAILED),
    ;

    companion object {
        /**
         * Decode a stored state. Anything else — the `running` older builds wrote — is owed work, as
         * `OcrQueueDao.nextPending` treats it.
         */
        fun fromColumn(value: String): QueueState = entries.firstOrNull { it.column == value } ?: PENDING
    }
}

/** This row's [QueueState]. */
val OcrQueueEntity.queueState: QueueState get() = QueueState.fromColumn(state)

/**
 * One page an OCR run read before the run as a whole failed, kept so that its retry pays only for
 * the pages still missing: a vision engine bills per page, and a scan that fails on page 150 would
 * otherwise buy the first 149 again on every attempt.
 *
 * Keyed by everything that makes the text what it is — the document's hash, the engine and the
 * languages it read — so a retry by another engine or in other languages reads afresh. Belongs to
 * the owed run in [OcrQueueEntity]: cleared when the run publishes its sidecar or the document
 * leaves the queue, and kept past the attempt cap, so a later explicit re-run resumes too.
 */
@Entity(
    tableName = "ocr_page_cache",
    primaryKeys = ["docId", "engine", "lang", "page"],
)
data class OcrPageCacheEntity(
    val docId: String,
    val engine: String,
    val lang: String,
    val page: Int,
    val text: String,
)

/**
 * Work list for the automatic post-OCR AI enrichment pass, in [DeviceDatabase]. A row means "this
 * document is owed an enrichment call"; the completed fact lives in [AiEnrichedEntity], so a
 * successful pass deletes the row rather than keeping a terminal state.
 *
 * The row has no state column at all. There is no `running` — the drain is single-flight in-process
 * and a row stays as it is while being worked on, so a process death mid-call leaves it due rather
 * than stranded — and no terminal `failed`, because a row that is owed a call is owed it until it
 * is made or the document stops being enrichable. Existence is the whole state; [nextAttemptAt]
 * says from when.
 */
@Entity(tableName = "ai_enrich_queue")
data class AiEnrichQueueEntity(
    @PrimaryKey val docId: String,
    /**
     * Failed attempts so far. Not a budget — the row is never given up on — but it does set
     * [nextAttemptAt] via the backoff, and orders the queue so retries yield to fresh work.
     */
    val attempts: Int = 0,
    /** Epoch millis before which this row must not be retried (exponential backoff). */
    val nextAttemptAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
)

/**
 * A document the automatic post-OCR AI enrichment pass already enriched on this device, by hash.
 * Device-local by nature (it guards spend on *this* device's endpoint), and kept out of the index
 * so that a rebuild does not let the same document cost again when it is OCR'd anew under a better
 * engine.
 */
@Entity(tableName = "ai_enriched")
data class AiEnrichedEntity(
    @PrimaryKey val docId: String,
    val enrichedAt: Long,
)

/**
 * A bulk AI edit the user handed to the background, in [DeviceDatabase]: the run's configuration,
 * plus the retry bookkeeping of its suggestion pass. The documents it covers live in
 * [AiEditJobDocEntity].
 *
 * Like [AiEnrichQueueEntity] there is no state column — a job's state is derived from its document
 * rows (all suggested = ready for review, some suggested = in progress, none = waiting) so the two
 * tables cannot disagree. The row is deleted when the user finishes reviewing it or discards it;
 * nothing else ends a job, because the suggestions it holds are the only copy of work already paid
 * for.
 */
@Entity(tableName = "ai_edit_jobs")
data class AiEditJobEntity(
    @PrimaryKey val jobId: String,
    /** The run's `AiEditRequest`, serialized; every document of the job is asked with it. */
    val requestJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Failed *whole-chunk* attempts; drives [nextAttemptAt] and orders the queue. */
    val attempts: Int = 0,
    /** Epoch millis before which this job must not be worked on again (exponential backoff). */
    val nextAttemptAt: Long,
    val lastError: String? = null,
    /** Requests reserved before dispatch, independent of the resettable spend ledger. */
    @androidx.room3.ColumnInfo(defaultValue = "0") val requestsUsed: Int = 0,
)

/**
 * One document of a queued AI edit, and its suggestion once the model has answered.
 *
 * [suggestionJson] null and [failed] false is the whole "still owed a call" state. A suggestion is
 * kept verbatim rather than as review rows: the Review is re-projected against the document's
 * *current* metadata when the user opens it, which can be hours later.
 */
@Entity(
    tableName = "ai_edit_job_docs",
    primaryKeys = ["jobId", "docId"],
    indices = [Index(value = ["jobId"])],
)
data class AiEditJobDocEntity(
    val jobId: String,
    val docId: String,
    /** Position in the submitted order, so the Review reads in the order the user chose. */
    val position: Int,
    /** Serialized `AiEditSuggestion`; null while the document is still owed a model call. */
    val suggestionJson: String? = null,
    val attempts: Int = 0,
    /** Given up on after the attempt cap; contributes no suggestions but is reported as a failure. */
    val failed: Boolean = false,
    val lastError: String? = null,
)

/**
 * A reminder this device already showed, in [DeviceDatabase]: one row per document, watched DATE
 * field and due date. It is what keeps the in-app check and the Android worker from repeating a
 * reminder for the same date; a changed date is a new row and does notify again. Device-local by
 * nature, so another device has its own and notifies once too.
 */
@Entity(
    tableName = "reminder_notifications",
    primaryKeys = ["docId", "fieldKey", "dueDate"],
)
data class ReminderNotificationEntity(
    val docId: String,
    val fieldKey: String,
    /** The due date the reminder was for, canonical `yyyy-MM-dd`. */
    val dueDate: String,
    val notifiedAt: Long,
)

/**
 * One AI request this device paid for, in [DeviceDatabase]. Device-local by nature: another
 * replica's endpoint, prices and currency are its own, so the ledger is never synced. It carries no
 * prompt or document text, only which provider and model answered, what kind of work it was, the
 * token usage (reported or estimated), the estimated cost and the run it belonged to.
 */
@Entity(
    tableName = "ai_usage",
    indices = [
        Index(value = ["recordedAt"]),
        Index(value = ["runId"]),
    ],
)
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The [dev.npn.core.ai.AiProvider] name, so a renamed product still reads back. */
    val provider: String,
    val model: String,
    /** An [dev.npn.core.ai.AiUsageKind] name. */
    val kind: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    /** US dollars from [dev.npn.core.ai.AiPricing]; null when the model has no known price. */
    val estimatedCostUsd: Double? = null,
    /** True when a token count is this device's estimate, not the provider's usage report. */
    val estimated: Boolean = false,
    val recordedAt: Long,
    /** The bulk run, queued job or other run id this request belongs to; null for a one-off. */
    val runId: String? = null,
)

/**
 * What this device last established about one file on the configured cloud remote, in
 * [DeviceDatabase]: the document (or trash entry) behind a remote path, and the size/mtime the
 * remote reported when that identity was established. The sync engine re-identifies an entry whose
 * size or mtime moved and guards every destructive remote call with the same pair, so a file
 * another writer replaced is never moved or deleted on the strength of stale knowledge.
 *
 * Here rather than in the index because nothing in the folder can rebuild it: losing it costs no
 * data, but re-identifying every remote PDF whose backend reports no SHA-256 means downloading it
 * again. Rows belong to the remote named in the `sync.remote` kv key; switching remotes clears the
 * table.
 */
@Entity(
    tableName = "sync_remote_entries",
    indices = [Index(value = ["docId"])],
)
data class SyncRemoteEntryEntity(
    @PrimaryKey val remotePath: String,
    /** `document` | `trash`; see `dev.npn.core.sync.ManifestKind`. */
    val kind: String,
    val size: Long,
    val mtime: Long,
    val docId: String?,
    val syncedAt: Long,
    /**
     * Where the document's file was in the library when this entry was last reconciled with it: how
     * a pass tells a document renamed here (the remote follows) from one renamed on the remote (this
     * library follows) when the journal names no path for it.
     */
    val localPath: String? = null,
)

/**
 * How far this device has read one mail folder, in [DeviceDatabase]: the mailbox generation
 * ([uidValidity]) and the last UID fully handled or deliberately skipped. Device-local by nature —
 * another device has its own IMAP session and may have read further — and never rebuildable from
 * the library, so it lives beside the index with real migrations.
 *
 * A failed message stays before [lastUid], so the next check sees it again; [lastError] is the last
 * typed reason, kept for the settings pane after a restart. No message content or password is here.
 */
@Entity(tableName = "mail_cursor", primaryKeys = ["accountKey", "folder"])
data class MailCursorEntity(
    val accountKey: String,
    val folder: String,
    val uidValidity: Long? = null,
    val lastUid: Long = 0,
    val lastCheckedAt: Long? = null,
    /** A [dev.npn.core.mail.MailFailureReason.name], or null after a clean check. */
    val lastError: String? = null,
)
