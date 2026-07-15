package dev.npn.core.db

import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Materialized document row. This is a disposable cache: it is a fold of the file library
 * plus the metadata journals and can be rebuilt from the folder alone (see PLAN.md sec. 1).
 *
 * docId is the SHA-256 hex of the file bytes (stable identity). Stored as a plain TEXT column;
 * the DocId value class is only used at higher layers.
 */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["relPath"], unique = true),
        Index(value = ["status"]),
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
    /** Wire value of [dev.npn.core.model.DocStatus]. */
    val status: String = "inbox",
    val correspondent: String? = null,
    val docType: String? = null,
    /** ISO-8601 local date, e.g. 2026-07-15. */
    val docDate: String? = null,
    val title: String? = null,
    val trashed: Boolean = false,
    val trashedOrigPath: String? = null,
    /** Tombstone: doc-delete journal event applied. */
    val deleted: Boolean = false,
    /** File disappeared from library, awaiting relink. */
    val missing: Boolean = false,
    /** none | pending | done | failed | adopted */
    val ocrState: String = "none",
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
 * FTS5 full-text index over the search axes (PLAN.md sec. 5: names, content, tags,
 * correspondent, document type). Plain (regular) FTS5 table: docId lives as an indexed
 * column and updates are done as exact-match delete + reinsert (see [FtsDao.replaceDocument]).
 *
 * KEY SPIKE (PLAN.md 0.2 / risk table): verify @Fts5 works on the JVM target with the
 * bundled SQLite driver.
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
)

/**
 * Tracks which journal segment files have been folded into this cache. Segments are
 * write-once immutable files, so a filename either is or isn't folded (PLAN.md sec. 2).
 */
@Entity(
    tableName = "folded_segments",
    primaryKeys = ["deviceId", "fileName"],
)
data class FoldedSegmentEntity(
    val deviceId: String,
    val fileName: String,
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
 * Extra paths holding the same content hash: externally created duplicates flagged for
 * cleanup (PLAN.md sec. 2: duplicates are forbidden, every hash has one canonical path).
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
