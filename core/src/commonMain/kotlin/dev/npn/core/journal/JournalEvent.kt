package dev.npn.core.journal

import kotlinx.serialization.Serializable

/**
 * A single journal event: the on-disk envelope plus its decoded operation.
 *
 * Wire form (one JSON object per line):
 * ```json
 * {"v":1,"seq":123,"hlc":{"t":..,"c":..,"d":".."},"dev":"..","doc":"<sha256 or null>","op":"tag-add","p":{...}}
 * ```
 *
 * - [dev] is the authoring actor: a fresh id per storage-v2 transaction. It always equals
 *   `hlc.deviceId`.
 * - [seq] is per actor, increasing, never reused.
 * - [doc] is the SHA-256 document id, or null for library-wide ops (tag-rename/merge/style).
 * - The dot of this event is `Dot(dev, seq)`.
 */
@Serializable(with = JournalEventSerializer::class)
internal data class JournalEvent(
    val version: Int,
    val seq: Long,
    val hlc: Hlc,
    val dev: String,
    val doc: String?,
    val op: JournalOp,
) {
    val dot: Dot get() = Dot(dev, seq)
}

/** Event-envelope versions are independent from the journal storage layout version. */
internal object JournalEventVersions {
    const val V1: Int = 1
    const val CANONICAL: Int = V1
}
