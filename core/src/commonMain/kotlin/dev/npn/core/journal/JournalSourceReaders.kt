package dev.npn.core.journal

import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.reason

internal sealed interface JournalSourceRead {
    data class Accepted(
        val events: List<CanonicalJournalEvent>,
    ) : JournalSourceRead

    data class Retryable(
        val message: String,
    ) : JournalSourceRead

    data class Unsupported(
        val message: String,
    ) : JournalSourceRead
}

/** Strict storage-v2 reader. Any integrity or structural failure applies zero events. */
internal object StorageV2Reader {
    private const val NOUN = "Object"

    private val uuidV4 =
        Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )

    /** Read the object at [relPath]; [contentHash] is [bytes]' SHA-256, when the caller has it already. */
    fun read(
        relPath: String,
        bytes: ByteArray,
        contentHash: String = sha256Bytes(bytes),
    ): JournalSourceRead {
        val path = JournalPaths.classify(relPath)
        val objectPath =
            path as? JournalPath.ObjectV2
                ?: return JournalSourceRead.Unsupported("Not a supported storage-v2 object path: $relPath")
        if (contentHash != objectPath.expectedHash) {
            return JournalSourceRead.Retryable("Object hash does not match its path")
        }
        return readVerified(bytes)
    }

    private fun readVerified(bytes: ByteArray): JournalSourceRead =
        StrictLines.read(bytes, NOUN) { lines ->
            val decoded = StrictLines.decodeEvents(lines, NOUN)
            if (decoded !is JournalSourceRead.Accepted) return@read decoded
            val violation = transactionViolation(decoded.events)
            if (violation == null) decoded else JournalSourceRead.Retryable(violation)
        }

    private fun transactionViolation(decoded: List<CanonicalJournalEvent>): String? {
        val actor = decoded.first().event.dev
        if (!uuidV4.matches(actor)) return "Transaction actor is not UUIDv4"
        var previous = Hlc.ZERO
        for ((index, canonical) in decoded.withIndex()) {
            val event = canonical.event
            if (event.dev != actor || event.hlc.deviceId != actor) {
                return "Object contains multiple transaction actors"
            }
            if (event.seq != index + 1L) {
                return "Object sequences must be exactly 1..N"
            }
            if (index > 0 && event.hlc <= previous) {
                return "Object HLCs must increase in sequence order"
            }
            previous = event.hlc
        }
        return null
    }
}

/**
 * The line format both content-addressed sources share, read strictly: UTF-8, one record per line
 * (`\n`, the last one optional), no blank line, and every event line a canonical event of a version
 * and an op this build knows. Any failure is a [JournalSourceRead] that applies nothing; [noun]
 * names the source in its message.
 */
internal object StrictLines {
    /** Hand [bytes]' lines to [decode], or refuse them. */
    inline fun read(
        bytes: ByteArray,
        noun: String,
        decode: (List<String>) -> JournalSourceRead,
    ): JournalSourceRead {
        val content =
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return JournalSourceRead.Retryable("$noun is not valid UTF-8")
            }
        val lines =
            content.split('\n').let { split ->
                if (split.lastOrNull()?.isEmpty() == true) split.dropLast(1) else split
            }
        if (lines.isEmpty()) return JournalSourceRead.Retryable("$noun is empty")
        if (lines.any { it.isBlank() }) return JournalSourceRead.Retryable("$noun contains a blank line")
        return decode(lines)
    }

    /** Decode every one of [lines] as an event, the first being line [firstLine] of the source. */
    fun decodeEvents(
        lines: List<String>,
        noun: String,
        firstLine: Int = 1,
    ): JournalSourceRead {
        val decoded = ArrayList<CanonicalJournalEvent>(lines.size)
        for ((index, line) in lines.withIndex()) {
            val number = firstLine + index
            val event =
                try {
                    EventDecoderRegistry.decode(line)
                } catch (e: UnsupportedEventVersionException) {
                    return JournalSourceRead.Unsupported(
                        "$noun line $number uses unsupported event version ${e.version}",
                    )
                } catch (e: Exception) {
                    return JournalSourceRead.Retryable("$noun line $number is invalid: ${e.reason()}")
                }
            if (event.event.op is JournalOp.Unknown) {
                return JournalSourceRead.Unsupported("$noun line $number uses unknown operation ${event.event.op.wire}")
            }
            decoded += event
        }
        return JournalSourceRead.Accepted(decoded)
    }
}
