package dev.npn.core.db

import androidx.room3.ColumnTypeConverter
import kotlin.jvm.JvmInline

/** [SourceStatus.APPLIED]'s stored value, for a query that filters on it. */
internal const val SOURCE_APPLIED = "applied"

/** Whether a journal source's events are in the accumulator. Stored as [wire]. */
enum class SourceStatus(
    val wire: String,
) {
    /** Read, checked, and its events recorded. */
    APPLIED(SOURCE_APPLIED),

    /** Not readable as a valid source (yet): a file still arriving, or a damaged one. Nothing recorded. */
    RETRYABLE("retryable"),
    ;

    companion object {
        fun fromWire(wire: String): SourceStatus =
            entries.firstOrNull { it.wire == wire } ?: error("Unknown metadata source status: $wire")
    }
}

/**
 * What kind of journal file a source is. Stored as [stored], the values the column held when it
 * mixed the on-disk format version (1, 2) with the snapshot kind (3).
 */
enum class SourceKind(
    val stored: Int,
) {
    /** A per-device v1 segment, `.npn/journal/<deviceId>/<seq>.jsonl`: still read, never written. */
    LEGACY_SEGMENT(1),

    /** A storage-v2 transaction object. */
    OBJECT(2),

    /** A compaction snapshot. */
    SNAPSHOT(3),
    ;

    companion object {
        fun fromStored(stored: Int): SourceKind =
            entries.firstOrNull { it.stored == stored } ?: error("Unknown metadata source kind: $stored")
    }
}

/**
 * A journal source's fingerprint as recorded when it was read, `"<size>,<mtime>:<sha256>"`: the
 * stat that lets an unchanged file skip the read, and the hash of the bytes that were read.
 */
@JvmInline
value class SourceFingerprint(
    val value: String,
) {
    constructor(size: Long, mtimeMillis: Long, contentHash: String) : this("$size,$mtimeMillis:$contentHash")

    /** The size recorded, or null when this fingerprint has none. */
    val size: Long? get() = stat.substringBefore(',').toLongOrNull()

    /** The mtime recorded, or null when this fingerprint has none. */
    val mtimeMillis: Long? get() = stat.substringAfter(',', missingDelimiterValue = "").toLongOrNull()

    /** Whether a file of [size] and [mtimeMillis] still has the stat recorded, so its bytes need no read. */
    fun sameStat(
        size: Long,
        mtimeMillis: Long,
    ): Boolean = stat == "$size,$mtimeMillis"

    private val stat: String get() = value.substringBefore(':', missingDelimiterValue = "")
}

/** Where the accumulator's projection stands. Stored as [wire]. */
enum class ProjectionStatus(
    val wire: String,
) {
    /** A reconcile is in flight, or died before the coordinator marked its projection ready. */
    RECONCILING("reconciling"),

    /** The projection is the fold of the applied sources it recorded. */
    READY("ready-v2"),

    /** A reconcile failed; writes stay refused until a pass succeeds. */
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(wire: String): ProjectionStatus =
            entries.firstOrNull { it.wire == wire } ?: error("Unknown metadata projection status: $wire")
    }
}

/** Room's conversions for the accumulator's typed columns. The stored values are the old strings and numbers. */
object MetadataColumnConverters {
    @ColumnTypeConverter
    fun sourceStatusToWire(status: SourceStatus): String = status.wire

    @ColumnTypeConverter
    fun sourceStatusFromWire(wire: String): SourceStatus = SourceStatus.fromWire(wire)

    @ColumnTypeConverter
    fun sourceKindToStored(kind: SourceKind): Int = kind.stored

    @ColumnTypeConverter
    fun sourceKindFromStored(stored: Int): SourceKind = SourceKind.fromStored(stored)

    @ColumnTypeConverter
    fun projectionStatusToWire(status: ProjectionStatus): String = status.wire

    @ColumnTypeConverter
    fun projectionStatusFromWire(wire: String): ProjectionStatus = ProjectionStatus.fromWire(wire)
}
