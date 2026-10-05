package dev.npn.core.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.npn.core.ocr.OcrState

/**
 * Materialized document row. This is a disposable cache: it is a fold of the file library
 * plus the metadata journals and can be rebuilt from the folder alone.
 *
 * docId is the SHA-256 hex of the file bytes (stable identity), stored as a plain TEXT column.
 */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["relPath"], unique = true),
        Index(value = ["reviewed"]),
        Index(value = ["correspondent"]),
        Index(value = ["docType"]),
        Index(value = ["docDate"]),
        Index(value = ["addedAt"]),
        Index(value = ["trashed"]),
        Index(value = ["deleted"]),
        Index(value = ["missing"]),
    ],
)
data class DocumentEntity(
    @PrimaryKey val docId: String,
    /** Canonical path inside the library folder. Unique: every hash has exactly one path. */
    val relPath: String,
    val fileName: String,
    val size: Long,
    val mtime: Long,
    val pageCount: Int? = null,
    /** First-seen epoch millis. */
    val addedAt: Long,
    /**
     * Manual "reviewed" marker used by the INBOX_WORKFLOW review mode. In UNTAGGED_ONLY mode this
     * is ignored (reviewed is derived as "has any tag").
     */
    val reviewed: Boolean = false,
    /**
     * READ CACHE of the well-known field rows in `document_fields`, nothing more. `correspondent`,
     * `docType`, `docDate`, `title` and `notes` are written only by the fold
     * ([dev.npn.core.index.Indexer]) from the same effective field map that produces the
     * `document_fields` rows, so the two never disagree after a fold. They exist so the list query
     * stays a single `ORDER BY` on one table; `document_fields` is the authoritative projection of
     * every user value, and no writer may set these columns directly.
     */
    val correspondent: String? = null,
    val docType: String? = null,
    /** ISO-8601 local date, e.g. 2026-07-15. Cache; see [correspondent]. */
    val docDate: String? = null,
    /** Cache; see [correspondent]. */
    val title: String? = null,
    /** Cache; see [correspondent]. */
    val notes: String? = null,
    val trashed: Boolean = false,
    val trashedOrigPath: String? = null,
    /**
     * While [trashed]: the wall time, in epoch millis, of the winning doc-trash event's HLC. The
     * trash auto-purge counts retention from it, because a move into trash keeps the PDF's own
     * mtime, which says when it was imported, not when it was trashed.
     */
    val trashedAt: Long? = null,
    /** Tombstone: doc-delete journal event applied. */
    val deleted: Boolean = false,
    /** File disappeared from library, awaiting relink. */
    val missing: Boolean = false,
    /** An [OcrState.column]. */
    val ocrState: String = OcrState.NONE.column,
    /**
     * Engine of the OCR sidecar currently indexed for search, an [dev.npn.core.ocr.OcrEngineId.column]
     * (null until first indexing).
     */
    val ocrEngine: String? = null,
    /**
     * User override of the searchable OCR engine, projected from the journal's
     * [dev.npn.core.model.SystemProperty.OCR_ENGINE] LWW property. Null = automatic (best engine).
     */
    val ocrEngineOverride: String? = null,
    /**
     * Writer key of the OCR sidecar currently indexed for search. [ocrEngine] alone cannot tell two
     * versions from the same engine apart, and a re-run adds exactly that.
     */
    val ocrSource: String? = null,
)

/** Whether the library shows this document: not trashed, not deleted, and on disk ([LIVE_DOCUMENT]). */
val DocumentEntity.isLive: Boolean get() = !trashed && !deleted && !missing

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val name: String,
    val color: String? = null,
    val icon: String? = null,
)

@Entity(
    tableName = "document_tags",
    primaryKeys = ["docId", "tag"],
    indices = [
        Index(value = ["docId"]),
        Index(value = ["tag"]),
    ],
)
data class DocumentTagEntity(
    val docId: String,
    val tag: String,
)

/**
 * One document's value for one registry field — title and correspondent as much as "amount".
 * Projected from the journal's [dev.npn.core.journal.JournalOp.FieldSet] LWW state; a cleared
 * field has no row, and a blank value is never stored. One row per (docId, key). This is the
 * authoritative projection of every user value; the typed columns on [DocumentEntity] cache the
 * well-known five.
 */
@Entity(
    tableName = "document_fields",
    primaryKeys = ["docId", "fieldKey"],
    indices = [
        Index(value = ["docId"]),
        Index(value = ["fieldKey"]),
        Index(value = ["fieldKey", "fieldValue"]),
        Index(value = ["fieldKey", "numValue"]),
    ],
)
data class DocumentFieldEntity(
    val docId: String,
    val fieldKey: String,
    /** Canonical form for the field's type (see [dev.npn.core.model.FieldValues]). */
    val fieldValue: String,
    /**
     * NUMBER/DECIMAL values parsed for correct sort and range filters; null for other types and
     * for off-type values, which therefore sort last. SQLite REAL loses precision above 2^53,
     * which is acceptable for document metadata.
     */
    val numValue: Double? = null,
)

/**
 * The field registry, projected from the journal's [dev.npn.core.journal.JournalOp.FieldDefSet]
 * LWW state so the UI and the AI tools read it as a Room Flow like everything else. One row per
 * live definition; a removed definition has no row. See [dev.npn.core.model.FieldDef].
 */
@Entity(
    tableName = "field_defs",
    indices = [Index(value = ["position"])],
)
data class FieldDefEntity(
    @PrimaryKey val fieldKey: String,
    val label: String,
    /** [dev.npn.core.model.FieldType.wire]. */
    val type: String,
    val showByDefault: Boolean,
    val aiEnabled: Boolean,
    val position: Int,
    val hint: String? = null,
    @ColumnInfo(defaultValue = "0") val perDocument: Boolean = false,
)

/**
 * FTS5 full-text index over file name, extracted content, tags and every field value. Plain
 * (regular) FTS5 table. FTS5 finds a row only by MATCH or by rowid: a `docId = ?` filter reads
 * every row, so a document's row is addressed by the rowid [FtsKeyEntity] records for it, never
 * through [docId], which is here for search results. Rows are built by
 * [dev.npn.core.index.FtsRows] only, so every write site indexes the same columns.
 * The JVM integration test verifies that [Fts5] works with the bundled SQLite driver.
 *
 * A schema change to this table needs a migration that keeps each row's rowid. Room's
 * auto-migration rebuilds an FTS table without them, which would leave [FtsKeyEntity] pointing at
 * the wrong rows.
 */
@Fts5
@Entity(tableName = "fts_documents")
data class FtsDocumentEntity(
    val docId: String,
    val fileName: String? = null,
    /** Embedded text + OCR text. */
    val content: String? = null,
    /** Space-joined tag names. */
    val tags: String? = null,
    /** Every field value the document carries (title, notes, custom fields alike), newline-joined. */
    val fields: String? = null,
)

/**
 * The rowid of each document's [FtsDocumentEntity] row, found through the unique index on [docId].
 * Every document-scoped FTS read and write resolves its row here (see [FtsDao]). Keys are allocated
 * here and never reused, so an FTS row is only ever inserted under its own key's rowid.
 */
@Entity(
    tableName = "fts_keys",
    indices = [Index(value = ["docId"], unique = true)],
)
data class FtsKeyEntity(
    @PrimaryKey(autoGenerate = true) val ftsRowid: Long = 0,
    val docId: String,
)

@Entity(tableName = "thumbnails")
data class ThumbnailEntity(
    @PrimaryKey val docId: String,
    val cachePath: String,
    val generatedAt: Long,
)

/**
 * Extra paths holding the same content hash. The document row retains the one canonical path;
 * externally created copies remain here until cleanup.
 */
@Entity(
    tableName = "duplicates",
    primaryKeys = ["docId", "relPath"],
)
data class DuplicateEntity(
    val docId: String,
    val relPath: String,
)

/**
 * Work list for the OCR pipeline, in [DeviceDatabase]. A row means "this document is owed an OCR
 * pass" on this device, and also holds what only this device knows about that pass: a re-run or an
 * AI run asked for here, the attempts and backoff spent, and (in [OcrPageCacheEntity]) the pages
 * already paid for. Unlike
 * [AiEnrichQueueEntity] it does keep terminal states: `done` is the history
 * `OcrPipeline.maybeQueueUpgrade` reads to decide whether a better engine is worth re-queueing,
 * and `failed` is the attempt cap being spent.
 *
 * There is no `running`, for the same reason [AiEnrichQueueEntity] has no state column at all —
 * learned the harder way here. The drain is single-flight in-process
 * ([dev.npn.core.work.QueueDrainer]), so the only thing that could write `running` was the pass
 * that was running, and the only thing that could clear it was that same pass surviving to the
 * end. It often did not: a killed process or a cancelled drain left the row marked running with
 * nobody left to tidy up, and a running row is one `OcrQueueDao.nextPending` never returns again.
 * The document became invisible to the drain permanently while still reading as busy to the user.
 *
 * So a row stays `pending` while it is being worked on, and an interrupted pass leaves it exactly
 * as it found it — owed, and taken by the next drain. Who is being worked on right now is
 * in-process knowledge (`OcrPipeline.inFlight`), which cannot outlive the process that knows it.
 */
@Entity(tableName = "ocr_queue")
data class OcrQueueEntity(
    @PrimaryKey val docId: String,
    /** A [QueueState.column] — see the class doc for why `running` is not one of them. */
    val state: String,
    val attempts: Int = 0,
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
 * Small key/value state. In [DeviceDatabase]: the journal device id, the installation it belongs
 * to, and the assistant's chats. In the index: where each document was last seen, observations a
 * rebuild makes again.
 */
@Entity(tableName = "kv")
data class KeyValueEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "metadata_sources")
data class MetadataSourceEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "storageVersion") val kind: SourceKind,
    val fingerprint: SourceFingerprint,
    val contentHash: String?,
    val status: SourceStatus,
    val diagnostic: String? = null,
)

@Entity(
    tableName = "metadata_events",
    primaryKeys = ["deviceId", "sequence"],
    indices = [
        Index(value = ["hlcPhysical", "hlcCounter", "hlcDevice"], unique = true),
        // MetadataProjectionDao.eventsForDocument filters on this; without the index it is a full
        // scan of a table that grows with every metadata edit ever made.
        Index(value = ["documentId"]),
    ],
)
data class MetadataEventEntity(
    val deviceId: String,
    val sequence: Long,
    val canonicalJson: String,
    val hlcPhysical: Long,
    val hlcCounter: Int,
    val hlcDevice: String,
    val documentId: String?,
    val operation: String,
)

@Entity(
    tableName = "metadata_source_events",
    primaryKeys = ["sourcePath", "eventDeviceId", "eventSequence"],
    indices = [Index(value = ["eventDeviceId", "eventSequence"])],
)
data class MetadataSourceEventEntity(
    val sourcePath: String,
    val eventDeviceId: String,
    val eventSequence: Long,
)

@Entity(tableName = "metadata_transaction_actors")
data class MetadataTransactionActorEntity(
    @PrimaryKey val actorId: String,
    val objectHash: String,
)

/**
 * Supersession fact recorded by an APPLIED compaction snapshot: [snapshotPath] supersedes the v2
 * object (or older snapshot) whose content hash is [supersededHash]. Rows are keyed by the pair so
 * two concurrent snapshots superseding the same object are both idempotently recorded. A missing
 * previously-applied source is tolerated iff its content hash appears here for an applied snapshot
 * (see MetadataAccumulator.validatePublishedObjects).
 */
@Entity(
    tableName = "metadata_superseded",
    primaryKeys = ["snapshotPath", "supersededHash"],
    indices = [Index(value = ["supersededHash"])],
)
data class MetadataSupersededEntity(
    val snapshotPath: String,
    val supersededHash: String,
)

@Entity(tableName = "metadata_projection_state")
data class MetadataProjectionStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val projectionVersion: Int,
    val migrationState: ProjectionStatus,
    val sourceMaxPhysical: Long,
    val sourceMaxCounter: Int,
    val sourceMaxDevice: String,
    val allocatorPhysical: Long,
    val allocatorCounter: Int,
    val diagnostic: String? = null,
    /**
     * Digest of the applied source set at the moment this projection was last marked ready — see
     * `MetadataAccumulator.sourceSetFingerprint`. When a fresh reconcile recomputes the same value
     * the journal has not changed since, so the stored projection is already the fold of exactly
     * these sources and the open path can skip refolding and reprojecting it. Null means unknown
     * (pre-upgrade row, or a projection that never reached ready), which always forces the refold.
     */
    val sourceSetFingerprint: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
