package dev.npn.core.journal

import dev.npn.core.fs.sha256Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JournalSourceReadersTest {
    private val actor = "12345678-1234-4234-9234-123456789abc"
    private val doc = "a".repeat(64)

    private fun event(seq: Long = 1L) =
        JournalEvent(
            version = JournalEventVersions.V1,
            seq = seq,
            hlc = Hlc(1000L, seq.toInt(), actor),
            dev = actor,
            doc = doc,
            op = JournalOp.TagAdd("invoice"),
        )

    private fun objectPath(bytes: ByteArray): String = JournalPaths.objectPath(sha256Bytes(bytes))

    @Test
    fun legacy_accepts_missing_version_and_reports_malformed_lines() {
        val missingVersion = JournalCodec.encode(event()).replaceFirst("\"v\":1,", "")
        val result =
            assertIs<JournalSourceRead.Accepted>(
                StorageV1Reader.read("$missingVersion\nnot-json".encodeToByteArray()),
            )
        assertEquals(1, result.events.size)
        assertEquals(1, result.diagnostics.size)
    }

    @Test
    fun v2_requires_explicit_version_and_applies_no_prefix() {
        val first = JournalCodec.encode(event()).replaceFirst("\"v\":1,", "")
        val bytes = first.encodeToByteArray()
        assertIs<JournalSourceRead.Retryable>(StorageV2Reader.read(objectPath(bytes), bytes))

        val partial = "${JournalCodec.encode(event())}\n{".encodeToByteArray()
        assertIs<JournalSourceRead.Retryable>(StorageV2Reader.read(objectPath(partial), partial))
    }

    @Test
    fun v2_rejects_hash_mismatch_and_unknown_event_version() {
        val valid = JournalCodec.encode(event()).encodeToByteArray()
        val wrongPath = JournalPaths.objectPath("0".repeat(64))
        assertIs<JournalSourceRead.Retryable>(StorageV2Reader.read(wrongPath, valid))

        val future = JournalCodec.encode(event()).replaceFirst("\"v\":1", "\"v\":99").encodeToByteArray()
        assertIs<JournalSourceRead.Unsupported>(StorageV2Reader.read(objectPath(future), future))
    }

    @Test
    fun v2_checks_the_hash_it_is_handed_rather_than_hashing_again() {
        val bytes = JournalCodec.encode(event()).encodeToByteArray()

        // The bytes match their path; only the handed hash does not, so a reader that re-hashed
        // would accept them.
        assertIs<JournalSourceRead.Retryable>(
            StorageV2Reader.read(objectPath(bytes), bytes, contentHash = "0".repeat(64)),
        )
        assertIs<JournalSourceRead.Accepted>(StorageV2Reader.read(objectPath(bytes), bytes, sha256Bytes(bytes)))
    }

    @Test
    fun v2_validates_complete_transaction() {
        val events = listOf(event(1), event(2))
        val bytes = JournalCodec.encodeLines(events).encodeToByteArray()
        val accepted = assertIs<JournalSourceRead.Accepted>(StorageV2Reader.read(objectPath(bytes), bytes))
        assertEquals(events, accepted.events.map { it.event })

        val gap = listOf(event(1), event(3))
        val gapBytes = JournalCodec.encodeLines(gap).encodeToByteArray()
        assertIs<JournalSourceRead.Retryable>(StorageV2Reader.read(objectPath(gapBytes), gapBytes))
    }

    @Test
    fun unknown_operation_payload_is_preserved() {
        val line =
            """{"v":1,"seq":1,"hlc":{"t":1,"c":0,"d":"$actor"},"dev":"$actor","doc":null,"op":"future","p":{"x":true}}"""
        val decoded = EventDecoderRegistry.decode(line, allowMissingV1 = false).event.op
        assertIs<JournalOp.Unknown>(decoded)
        assertTrue(decoded.payload.containsKey("x"))
        assertTrue(JournalCodec.encode(event().copy(op = decoded)).contains("\"x\":true"))
    }

    @Test
    fun duplicate_json_keys_are_rejected() {
        val line =
            """{"v":1,"v":1,"seq":1,"hlc":{"t":1,"c":0,"d":"$actor"},"dev":"$actor","doc":null,"op":"future","p":{}}"""
        val bytes = line.encodeToByteArray()
        assertIs<JournalSourceRead.Retryable>(StorageV2Reader.read(objectPath(bytes), bytes))
    }

    @Test
    fun unknown_operations_and_string_versions_block_source_ingestion() {
        val unknown =
            """{"v":1,"seq":1,"hlc":{"t":1,"c":0,"d":"$actor"},"dev":"$actor","doc":null,"op":"future","p":{}}"""
        assertIs<JournalSourceRead.Unsupported>(StorageV1Reader.read(unknown.encodeToByteArray()))

        val stringVersion = unknown.replace("\"v\":1", "\"v\":\"1\"")
        val bytes = stringVersion.encodeToByteArray()
        assertIs<JournalSourceRead.Retryable>(StorageV2Reader.read(objectPath(bytes), bytes))
    }
}
