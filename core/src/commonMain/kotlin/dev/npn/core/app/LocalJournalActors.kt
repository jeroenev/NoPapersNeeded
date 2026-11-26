package dev.npn.core.app

import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.KeyValueEntity
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent

/**
 * The journal authors this installation has published under, kept in `device.db`'s existing
 * key-value table. A journal transaction carries a fresh actor id rather than the device id
 * (`ContentAddressedJournalWriter`), so nothing in the journal itself says an event is this
 * installation's; a history row can only be told apart from another device's by this record.
 *
 * One row per actor, its value the HLC it published at, written once after the object is durable;
 * and the HLC recording starts from ([SINCE]): the first write recorded. A change older than that
 * may be this installation's (an earlier build recorded nothing) or another's, so it is of unknown
 * origin rather than another device's. Past [keep] actors the oldest are pruned and the start
 * moves up to the oldest kept, so their changes read as unknown too.
 */
internal class LocalJournalActors(
    private val kv: KeyValueDao,
    private val keep: Int = KEEP,
    private val pruneEvery: Int = PRUNE_EVERY,
) {
    private var recorded = 0

    /** Record the [events] a transaction this installation just published was made of. */
    suspend fun record(events: List<JournalEvent>) {
        val actors = events.groupBy { it.dev }.mapValues { (_, own) -> encode(own.minOf { it.hlc }) }
        if (actors.isEmpty()) return
        kv.putAll(actors.map { (actor, at) -> KeyValueEntity(key(actor), at) })
        // A seeding transaction sits beneath every edit (physical time 0): it dates nothing.
        events
            .filter { it.hlc.physicalMillis > 0 }
            .minOfOrNull { it.hlc }
            ?.let { kv.putMissing(listOf(KeyValueEntity(SINCE, encode(it)))) }
        if (recorded++ % pruneEvery == 0) prune()
    }

    /** Where each of [rows] (an event's actor and HLC) came from, in order. */
    suspend fun origins(rows: List<Pair<String, Hlc>>): List<HistoryOrigin> {
        if (rows.isEmpty()) return emptyList()
        val local = among(rows.mapTo(LinkedHashSet()) { it.first })
        val since = kv.get(SINCE)
        return rows.map { (actor, hlc) ->
            when {
                actor in local -> HistoryOrigin.THIS_DEVICE
                since != null && encode(hlc) >= since -> HistoryOrigin.ANOTHER_DEVICE
                else -> HistoryOrigin.UNKNOWN
            }
        }
    }

    /** Which of [actors] this installation published. */
    private suspend fun among(actors: Collection<String>): Set<String> {
        val wanted = actors.associateBy(::key)
        return kv.getAll(wanted.keys.toList()).keys.mapNotNullTo(HashSet()) { wanted[it] }
    }

    private suspend fun prune() {
        if (kv.countWithPrefix(PREFIX) <= keep) return
        val oldestKept = kv.valueWithPrefixFromNewest(PREFIX, keep - 1) ?: return
        kv.deleteWithPrefixBelow(PREFIX, oldestKept)
        val since = kv.get(SINCE)
        if (since == null || since < oldestKept) kv.put(SINCE, oldestKept)
    }

    private companion object {
        const val PREFIX = "journal.actor."

        /** The HLC this installation's actors are recorded from. */
        const val SINCE = "journal.actors-since"

        const val KEEP = 10_000
        const val PRUNE_EVERY = 256
        const val PHYSICAL_DIGITS = 16
        const val COUNTER_DIGITS = 10

        fun key(actor: String): String = "$PREFIX$actor"

        /** [hlc]'s time as fixed-width text, so stored values compare as the HLCs do. */
        fun encode(hlc: Hlc): String =
            hlc.physicalMillis.toString().padStart(PHYSICAL_DIGITS, '0') + "." +
                hlc.counter.toString().padStart(COUNTER_DIGITS, '0')
    }
}
