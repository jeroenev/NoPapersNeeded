package dev.npn.app

import dev.npn.core.app.FieldRegistry
import dev.npn.core.db.FieldKeyCount
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory field registry. Starts seeded with the well-known five so viewmodels see the same
 * registry a fresh library gets; tests mutate [defs] directly or through the write methods.
 */
class FakeFieldRegistry : FieldRegistry {
    val defs = MutableStateFlow(FieldKeys.seedDefinitions { FieldKeys.SEED_LABELS.getValue(it) })
    val orphans = MutableStateFlow<List<FieldKeyCount>>(emptyList())
    val values = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    val upserts = mutableListOf<FieldDef>()
    val removals = mutableListOf<Pair<String, Boolean>>()
    val reorders = mutableListOf<List<String>>()

    override val fields: Flow<List<FieldDef>> get() = defs
    override val visible: Flow<List<FieldDef>> get() = defs.map { list -> list.filter { it.showByDefault } }
    override val aiFields: Flow<List<FieldDef>> get() = defs.map { list -> list.filter { it.aiEnabled } }

    override fun orphanKeys(): Flow<List<FieldKeyCount>> = orphans

    val usage = MutableStateFlow<Map<String, Int>>(emptyMap())

    override fun usageCounts(): Flow<Map<String, Int>> = usage

    override fun distinctValues(
        key: String,
        limit: Int,
    ): Flow<List<String>> = values.map { it[key].orEmpty().take(limit) }

    override suspend fun distinctValuesNow(
        key: String,
        limit: Int,
    ): List<String> = values.value[key].orEmpty().take(limit)

    override suspend fun valuesForPrefix(
        key: String,
        prefix: String,
        limit: Int,
    ): List<String> =
        values.value[key]
            .orEmpty()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .take(limit)

    override suspend fun current(): List<FieldDef> = defs.value

    override suspend fun upsert(def: FieldDef) {
        upserts += def
        defs.value = (defs.value.filter { it.key != def.key } + def).sortedWith(compareBy({ it.position }, { it.key }))
    }

    override suspend fun remove(
        key: String,
        clearValues: Boolean,
    ) {
        removals += key to clearValues
        defs.value = defs.value.filter { it.key != key }
    }

    override suspend fun reorder(keys: List<String>) {
        reorders += keys
        val byKey = defs.value.associateBy { it.key }
        val ordered = keys.mapNotNull { byKey[it] } + defs.value.filter { it.key !in keys }
        defs.value = ordered.mapIndexed { i, d -> d.copy(position = i) }
    }
}
