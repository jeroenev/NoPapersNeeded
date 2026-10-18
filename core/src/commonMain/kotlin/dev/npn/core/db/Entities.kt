package dev.npn.core.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.npn.core.ocr.OcrState

// The index's entities (NpnDatabase): a disposable cache whose builders fall back to a destructive
// migration. The entities of DeviceDatabase, which is never dropped, live in DeviceEntities.kt.

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
 * Cache of the library's rule list, whose master copy is a synced setting in the journal
 * (`dev.npn.core.app.AutoMatchRuleSource` keeps the two in step). Disposable like the
 * rest of the index.
 */
@Entity(tableName = "auto_match_rules")
data class AutoMatchRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    /** [dev.npn.core.rules.RuleField.wire]. */
    val field: String,
    val algorithm: String,
    val pattern: String,
    /** Comma-separated tag names to add when the rule matches. */
    val tags: String?,
    /** JSON object of field key -> value to set when the rule matches. */
    @ColumnInfo(defaultValue = "{}") val sets: String,
    val position: Int,
)

/**
 * Small key/value state. In [DeviceDatabase]: the journal device id, the installation it belongs
 * to, and the assistant's chats. In the index: where each document was last seen, observations a
 * rebuild makes again. Shared by both, so it stays here, but a change to it is a [DeviceDatabase]
 * change too: it needs that database's migration (see DeviceEntities.kt).
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
 * still on disk, directly or through a snapshot that one superseded, or, for a missing snapshot,
 * when what it superseded is on disk (see `SnapshotSupport.carriedByListed`). A claim on a
 * source still on disk whose events the snapshot does not hold is dropped by the reconcile that finds
 * it (see `SnapshotSupport.deleteSupersededLeftovers`), so it vouches for nothing after.
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
