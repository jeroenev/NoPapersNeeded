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
 * - [seq] is per-device, monotonically increasing across all segments, never reused.
 * - [dev] is the authoring device id. It always equals `hlc.deviceId`.
 * - [doc] is the SHA-256 document id, or null for library-wide ops (tag-rename/merge/style).
 * - The dot of this event is `Dot(dev, seq)`.
 */
@Serializable(with = JournalEventSerializer::class)
data class JournalEvent(
    val version: Int,
    val seq: Long,
    val hlc: Hlc,
    val dev: String,
    val doc: String?,
    val op: JournalOp,
) {
    val dot: Dot get() = Dot(dev, seq)

    companion object {
        const val CURRENT_VERSION = 1
    }
}
