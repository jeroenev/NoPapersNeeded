package dev.npn.core.index

import dev.npn.core.db.KeyValueDao
import dev.npn.core.journal.LocalJournalState
import dev.npn.core.util.NpnJson
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * [LocalJournalState] backed by the local index database's `kv` table. This is device-private
 * bookkeeping (last assigned seq + known own segment file names for fork detection); it lives in
 * the disposable cache, never inside the synced library folder.
 *
 * The known-own-segments set is JSON-encoded into a single kv row.
 */
class DbLocalJournalState(
    private val kv: KeyValueDao,
) : LocalJournalState {
    override suspend fun getLastSeq(): Long? = kv.get(KEY_LAST_SEQ)?.toLongOrNull()

    override suspend fun setLastSeq(seq: Long) {
        kv.put(KEY_LAST_SEQ, seq.toString())
    }

    override suspend fun getKnownOwnSegments(): Set<String> {
        val raw = kv.get(KEY_KNOWN_SEGMENTS) ?: return emptySet()
        return runCatching { json.decodeFromString(setSerializer, raw) }.getOrElse { emptySet() }
    }

    override suspend fun setKnownOwnSegments(fileNames: Set<String>) {
        kv.put(KEY_KNOWN_SEGMENTS, json.encodeToString(setSerializer, fileNames))
    }

    override suspend fun reset() {
        kv.delete(KEY_LAST_SEQ)
        kv.delete(KEY_KNOWN_SEGMENTS)
    }

    companion object {
        const val KEY_LAST_SEQ = "journal.lastSeq"
        const val KEY_KNOWN_SEGMENTS = "journal.knownOwnSegments"
        private val json = NpnJson.Persisted
        private val setSerializer = SetSerializer(String.serializer())
    }
}
