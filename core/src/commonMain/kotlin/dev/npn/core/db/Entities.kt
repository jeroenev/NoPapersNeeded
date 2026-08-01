package dev.npn.core.db

import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Materialized document row. This is a disposable cache: it is a fold of the file library
 * plus the metadata journals and can be rebuilt from the folder alone.
 *
 * docId is the SHA-256 hex of the file bytes (stable identity). Stored as a plain TEXT column;
 * the DocId value class is only used at higher layers.
 */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["relPath"], unique = true),
        Index(value = ["reviewed"]),
        Index(value = ["correspondent"]),
        Index(value = ["docType"]),
        Index(value = ["docDate"]),
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
    val correspondent: String? = null,
    val docType: String? = null,
    /** ISO-8601 local date, e.g. 2026-07-15. */
    val docDate: String? = null,
    val title: String? = null,
    /** User-authored free-text note, projected from the immutable journal. */
    val notes: String? = null,
    val trashed: Boolean = false,
    val trashedOrigPath: String? = null,
    /** Tombstone: doc-delete journal event applied. */
    val deleted: Boolean = false,
    /** File disappeared from library, awaiting relink. */
    val missing: Boolean = false,
    /** none | pending | done | failed | adopted */
    val ocrState: String = "none",
    /** Engine of the OCR sidecar currently indexed for search (null until first indexing). */
    val ocrEngine: String? = null,
    /**
     * User override of the searchable OCR engine, projected from the journal's
     * [dev.npn.core.model.DocProperty.OCR_ENGINE] LWW property. Null = automatic (best engine).
     */
    val ocrEngineOverride: String? = null,
)

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
 * Per-document custom metadata field: a free-form string key (e.g. "amount") and value. Projected
 * from the journal's [dev.npn.core.journal.JournalOp.FieldSet] LWW state; a cleared field has no
 * row. One row per (docId, key).
 */
@Entity(
    tableName = "document_fields",
    primaryKeys = ["docId", "fieldKey"],
    indices = [
        Index(value = ["docId"]),
        Index(value = ["fieldKey"]),
    ],
)
data class DocumentFieldEntity(
    val docId: String,
    val fieldKey: String,
    val fieldValue: String,
)

/**
 * FTS5 full-text index over names, content, tags, correspondent, and document type. Plain
 * (regular) FTS5 table: docId lives as an indexed
 * column and updates are done as exact-match delete + reinsert (see [FtsDao.replaceDocument]).
 * The JVM integration test verifies that [Fts5] works with the bundled SQLite driver.
 */
@Fts5
@Entity(tableName = "fts_documents")
data class FtsDocumentEntity(
    val docId: String,
    val fileName: String? = null,
    val title: String? = null,
    /** Embedded text + OCR text. */
    val content: String? = null,
    /** Space-joined tag names. */
    val tags: String? = null,
    val correspondent: String? = null,
    val docType: String? = null,
    /** User notes are indexed separately from extracted PDF/OCR content. */
    val notes: String? = null,
)

@Entity(tableName = "saved_views")
data class SavedViewEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** filter + sort + view-type serialized by the upper layer. */
    val json: String,
    val pinned: Boolean = false,
    val position: Int = 0,
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

@Entity(tableName = "ocr_queue")
data class OcrQueueEntity(
    @PrimaryKey val docId: String,
    /** pending | running | done | failed */
    val state: String,
    val attempts: Int = 0,
    val updatedAt: Long,
    val lastError: String? = null,
)

/** Small local state: deviceId, last journal seq, known own segments as JSON, schema markers. */
@Entity(tableName = "kv")
data class KeyValueEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "metadata_sources")
data class MetadataSourceEntity(
    @PrimaryKey val path: String,
    val storageVersion: Int,
    val fingerprint: String,
    val contentHash: String?,
    val status: String,
    val diagnostic: String? = null,
)

@Entity(
    tableName = "metadata_events",
    primaryKeys = ["deviceId", "sequence"],
    indices = [Index(value = ["hlcPhysical", "hlcCounter", "hlcDevice"], unique = true)],
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
    val migrationState: String,
    val sourceMaxPhysical: Long,
    val sourceMaxCounter: Int,
    val sourceMaxDevice: String,
    val allocatorPhysical: Long,
    val allocatorCounter: Int,
    val diagnostic: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
