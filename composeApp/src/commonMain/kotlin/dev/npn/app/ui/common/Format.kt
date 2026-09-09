package dev.npn.app.ui.common

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Maximum entries shown in an inline autocomplete list (tag / correspondent / doc-type / field-key
 * suggestions). Shared so the various editor fields cap their dropdowns identically.
 */
const val MAX_INLINE_SUGGESTIONS = 8

/** Human-readable file size. */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.roundTo(1)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${mb.roundTo(1)} MB"
    val gb = mb / 1024.0
    return "${gb.roundTo(1)} GB"
}

private fun Double.roundTo(decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    val rounded = kotlin.math.round(this * factor) / factor
    val s = rounded.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/** Epoch millis -> yyyy-MM-dd local date. */
fun formatEpochDate(epochMillis: Long): String {
    val dt =
        Instant
            .fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val m = (dt.month.ordinal + 1).toString().padStart(2, '0')
    val d = dt.day.toString().padStart(2, '0')
    return "${dt.year}-$m-$d"
}

/** Epoch millis -> yyyy-MM-dd HH:mm:ss in the current local time zone. */
fun formatEpochDateTime(epochMillis: Long): String {
    val dt =
        Instant
            .fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val month = (dt.month.ordinal + 1).toString().padStart(2, '0')
    val day = dt.day.toString().padStart(2, '0')
    val hour = dt.hour.toString().padStart(2, '0')
    val minute = dt.minute.toString().padStart(2, '0')
    val second = dt.second.toString().padStart(2, '0')
    return "${dt.year}-$month-$day $hour:$minute:$second"
}
