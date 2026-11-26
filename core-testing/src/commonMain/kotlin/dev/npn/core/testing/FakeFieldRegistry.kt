package dev.npn.core.testing

import dev.npn.core.app.FieldRegistry
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory field registry. Starts seeded with the well-known five so callers see the same
 * registry a fresh library gets; tests mutate [defs] directly or through the write methods.
 */
class FakeFieldRegistry : FieldRegistry {
    val defs = MutableStateFlow(FieldKeys.seedDefinitions { FieldKeys.SEED_LABELS.getValue(it) })
    val orphans = MutableStateFlow<List<String>>(emptyList())
    val values = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val usage = MutableStateFlow<Map<String, Int>>(emptyMap())

    val upserts = mutableListOf<FieldDef>()
    val removals = mutableListOf<Pair<String, Boolean>>()
    val moves = mutableListOf<Pair<String, Int>>()

    override val fields: Flow<List<FieldDef>> get() = defs

    /** The E-invoice fields setting; the fake only records it, it adds no definitions. */
    val eInvoice = MutableStateFlow(false)

    override val eInvoiceFields: Flow<Boolean> get() = eInvoice

    override suspend fun setEInvoiceFields(enabled: Boolean) {
        eInvoice.value = enabled
    }

    override fun orphanKeys(): Flow<List<String>> = orphans

    override fun usageCounts(): Flow<Map<String, Int>> = usage

    override fun distinctValues(
        key: String,
        limit: Int,
    ): Flow<List<String>> = values.map { it[key].orEmpty().take(limit) }

    override suspend fun current(): List<FieldDef> = defs.value

    override suspend fun upsert(def: FieldDef) {
        upserts += def
        val previous = defs.value.firstOrNull { it.key == def.key }
        val placed =
            if (previous == null && def.position < 0) {
                def.copy(position = (defs.value.maxOfOrNull { it.position } ?: -1) + 1)
            } else {
                def
            }
        defs.value =
            (defs.value.filter { it.key != def.key } + placed).sortedWith(compareBy({ it.position }, { it.key }))
    }

    override suspend fun remove(
        key: String,
        clearValues: Boolean,
    ) {
        removals += key to clearValues
        defs.value = defs.value.filter { it.key != key }
    }

    override suspend fun move(
        key: String,
        delta: Int,
    ) {
        moves += key to delta
        val list = defs.value.toMutableList()
        val from = list.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        defs.value = list.mapIndexed { i, d -> d.copy(position = i) }
    }
}
