package dev.npn.core.journal

import kotlinx.serialization.json.Json

/**
 * Reads and writes the JSON-lines segment format: one [JournalEvent] per line.
 *
 * Reading is tolerant: blank lines are skipped, and a line that fails to parse (corrupt or
 * a future event shape we cannot handle) is skipped rather than aborting the whole segment.
 * Unknown *ops* still parse into [JournalOp.Unknown] via the serializer; this line-level
 * skip only catches structurally-broken lines.
 */
object JournalCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(event: JournalEvent): String = json.encodeToString(JournalEventSerializer, event)

    fun encodeLines(events: List<JournalEvent>): String =
        events.joinToString("\n") { encode(it) }

    /** Parse a single line, or null if blank/unparseable. */
    fun decodeLineOrNull(line: String): JournalEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return try {
            json.decodeFromString(JournalEventSerializer, trimmed)
        } catch (_: Exception) {
            null
        }
    }

    /** Parse a whole segment body, skipping blank/unparseable lines. */
    fun decode(content: String): List<JournalEvent> =
        content.lineSequence().mapNotNull { decodeLineOrNull(it) }.toList()
}
