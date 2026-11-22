package dev.npn.core.app

import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.KeyValueEntity
import dev.npn.core.journal.JournalEvent

/**
 * The journal authors this installation has published under, kept in `device.db`'s existing
 * key-value table. A journal transaction carries a fresh actor id rather than the device id
 * (`ContentAddressedJournalWriter`), so nothing in the journal itself says an event is this
 * installation's; a history row can only be told apart from another device's by this record.
 *
 * One row per actor, written once after the object is durable. Actors this build did not see
 * published (everything an earlier build wrote) stay unrecorded and read as another device's.
 */
internal class LocalJournalActors(
    private val kv: KeyValueDao,
) {
    /** Record the [events] a transaction this installation just published was made of. */
    suspend fun record(events: List<JournalEvent>) {
        val actors = events.mapTo(LinkedHashSet()) { it.dev }
        if (actors.isNotEmpty()) kv.putAll(actors.map { KeyValueEntity(key(it), "1") })
    }

    /** Which of [actors] this installation published: the rest are another device's. */
    suspend fun among(actors: Collection<String>): Set<String> {
        if (actors.isEmpty()) return emptySet()
        val wanted = actors.associateBy(::key)
        return kv.getAll(wanted.keys.toList()).keys.mapNotNullTo(HashSet()) { wanted[it] }
    }

    private companion object {
        const val PREFIX = "journal.actor."

        fun key(actor: String): String = "$PREFIX$actor"
    }
}
