package dev.npn.core.app

import dev.npn.core.db.FieldKeyCount
import dev.npn.core.model.FieldDef
import kotlinx.coroutines.flow.Flow

/**
 * The library's field registry: which document fields exist, what they are called, how they are
 * typed, and which of them render on every document or get offered to the AI. Definitions are
 * synced journal state ([dev.npn.core.journal.JournalOp.FieldDefSet]); the flows read the Room
 * projection of that state.
 */
interface FieldRegistry {
    /** Every live definition, in registry order (position, then key). */
    val fields: Flow<List<FieldDef>>

    /** Keys that carry values on some document but have no definition, with document counts. */
    fun orphanKeys(): Flow<List<FieldKeyCount>>

    /** How many documents carry a value for each key (registered or not); absent keys have none. */
    fun usageCounts(): Flow<Map<String, Int>>

    /** Distinct stored values for [key], for autocomplete and AI vocabulary. Case-insensitive order. */
    fun distinctValues(
        key: String,
        limit: Int = DEFAULT_VALUE_LIMIT,
    ): Flow<List<String>>

    /** The current registry, read once. */
    suspend fun current(): List<FieldDef>

    /**
     * Define or update a field. The key is normalized through [dev.npn.core.model.FieldKeys.normalize];
     * a definition whose normalized key collides case-insensitively with a *different* existing key
     * is rejected with [IllegalArgumentException]. Well-known keys keep their seeded type.
     */
    suspend fun upsert(def: FieldDef)

    /**
     * Remove a definition. With [clearValues], also clear the field on every document carrying it,
     * in the same transaction. Without it the values stay as orphans.
     */
    suspend fun remove(
        key: String,
        clearValues: Boolean = false,
    )

    /** Rewrite positions so the registry follows [keys]; keys not listed keep their relative order after. */
    suspend fun reorder(keys: List<String>)

    companion object {
        const val DEFAULT_VALUE_LIMIT = 200
    }
}
