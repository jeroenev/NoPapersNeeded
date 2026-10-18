package dev.npn.core.journal

/**
 * Parse one journal line as a published object's reader does, or null when it is blank or does not
 * parse (corrupt, or a shape this build cannot handle). An unknown op still parses, into
 * [JournalOp.Unknown]; the null only catches structurally broken lines.
 */
fun JournalCodec.decodeLineOrNull(line: String): JournalEvent? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    return try {
        EventDecoderRegistry.decode(trimmed, allowMissingV1 = true).event
    } catch (_: Exception) {
        null
    }
}
