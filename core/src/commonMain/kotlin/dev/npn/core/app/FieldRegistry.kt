package dev.npn.core.app

import dev.npn.core.model.FieldDef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The library's field registry: which document fields exist, what they are called, how they are
 * typed, and which of them render on every document or get offered to the AI. Definitions are
 * synced journal state ([dev.npn.core.journal.JournalOp.FieldDefSet]); the flows read the Room
 * projection of that state.
 */
interface FieldRegistry {
    /** Every live definition, in registry order (position, then key). */
    val fields: Flow<List<FieldDef>>

    /** Keys that carry values on some document but have no definition, case-insensitively ordered. */
    fun orphanKeys(): Flow<List<String>>

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
     * is rejected with [IllegalArgumentException]. Well-known keys keep their seeded type. A new
     * definition at [FieldDef.PLACE_LAST] (any negative position) goes after every other.
     */
    suspend fun upsert(def: FieldDef)

    /**
     * Remove a definition. With [clearValues], also clear the field on every document the journal
     * has carrying it, trashed ones and ones without an index row included, in the same transaction.
     * Without it the values stay as orphans.
     */
    suspend fun remove(
        key: String,
        clearValues: Boolean = false,
    )

    /**
     * Move [key] [delta] places in the registry order (negative is earlier), reading the order and
     * rewriting it in one transaction. A key it does not list, or a move past either end, changes
     * nothing.
     */
    suspend fun move(
        key: String,
        delta: Int,
    )

    companion object {
        const val DEFAULT_VALUE_LIMIT = 200
    }
}

/**
 * The distinct stored values of every registry field whose type has a vocabulary
 * ([dev.npn.core.model.FieldType.hasVocabulary]): key -> values, for filters to pick from. Follows
 * the registry, so a newly defined TEXT field starts offering its values as soon as documents carry
 * any.
 */
fun FieldRegistry.textFieldValues(): Flow<Map<String, List<String>>> = valuesOf { it.type.hasVocabulary }

/**
 * The values an editor offers for reuse: [textFieldValues] of only the fields whose documents share
 * them ([FieldDef.sharesVocabulary]). A [FieldDef.perDocument] field, such as the title, offers none.
 */
fun FieldRegistry.reusableFieldValues(): Flow<Map<String, List<String>>> = valuesOf { it.sharesVocabulary }

@OptIn(ExperimentalCoroutinesApi::class)
private fun FieldRegistry.valuesOf(include: (FieldDef) -> Boolean): Flow<Map<String, List<String>>> =
    fields.flatMapLatest { defs ->
        val listed = defs.filter(include)
        if (listed.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(listed.map { def -> distinctValues(def.key).map { def.key to it } }) { it.toMap() }
        }
    }
