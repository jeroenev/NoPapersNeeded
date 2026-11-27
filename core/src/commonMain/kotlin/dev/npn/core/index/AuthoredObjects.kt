package dev.npn.core.index

import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.KeyValueEntity

/**
 * The journal objects this installation published that no acknowledgment record of its own vouches
 * for yet ([AckRecords]), kept in `device.db`'s key-value table. A transaction carries a fresh actor
 * id rather than the device id, so nothing in an object says which device wrote it: only this list
 * does, and a record lists exactly what its device published.
 *
 * An object's hash goes in before the object is published, so a crash in between leaves it here:
 * a record lists only those whose object is in the folder, and forgets one that never was.
 */
internal class AuthoredObjects(
    private val kv: KeyValueDao,
) {
    /** Note the object [hash] as this installation's, before it is published. */
    suspend fun add(hash: String) = kv.putMissing(listOf(KeyValueEntity(key(hash), "")))

    /** Every object noted and not yet [forget]-ten, by hash, in order. */
    suspend fun pending(): List<String> = kv.keysWithPrefix(PREFIX).map { it.removePrefix(PREFIX) }

    /** Forget [hashes]: a record of this installation's vouches for them now, or they were never published. */
    suspend fun forget(hashes: Collection<String>) = kv.deleteAll(hashes.map(::key))

    private companion object {
        const val PREFIX = "journal.authored."

        fun key(hash: String) = "$PREFIX$hash"
    }
}
