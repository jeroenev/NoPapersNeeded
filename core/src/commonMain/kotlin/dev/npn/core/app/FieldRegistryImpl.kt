package dev.npn.core.app

import dev.npn.core.db.FieldKeyCount
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.toFieldDef
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Registry reads come from the `field_defs` projection; writes are `field-def` journal ops
 * validated against the live [FoldedState] under the coordinator mutex, so a key collision check
 * and the write it guards cannot be split by a concurrent commit. Writes run through [owned], so
 * the session waits them out before it closes.
 */
class FieldRegistryImpl internal constructor(
    private val db: NpnDatabase,
    private val coordinator: JournalCoordinator,
    private val owned: suspend (suspend () -> Unit) -> Unit,
) : FieldRegistry {
    private val defDao get() = db.fieldDefDao()
    private val fieldDao get() = db.documentFieldDao()

    override val fields: Flow<List<FieldDef>> =
        defDao.observeAll().map { rows -> rows.mapNotNull { it.toFieldDef() } }.distinctUntilChanged()

    override fun orphanKeys(): Flow<List<FieldKeyCount>> = defDao.observeOrphanKeys()

    override fun usageCounts(): Flow<Map<String, Int>> =
        fieldDao.observeKeysWithCounts().map { rows -> rows.associate { it.fieldKey to it.count } }

    override fun distinctValues(
        key: String,
        limit: Int,
    ): Flow<List<String>> = fieldDao.observeDistinctValues(key, limit)

    override suspend fun current(): List<FieldDef> = defDao.all().mapNotNull { it.toFieldDef() }

    override suspend fun upsert(def: FieldDef) =
        owned {
            val key = FieldKeys.normalize(def.key) ?: throw IllegalArgumentException("Field key is empty")
            val label = def.label.trim().ifEmpty { key }
            coordinator.commitBuilt { state ->
                val existing = state.effectiveFieldDefs()
                val collision = existing.firstOrNull { it.key != key && FieldKeys.sameKey(it.key, key) }
                require(collision == null) { "A field with key '${collision?.key}' already exists" }
                val previous = state.effectiveFieldDef(key)
                // Well-known keys keep their seeded type: templates like {docDate:yyyy} rely on it.
                val type = if (key in FieldKeys.WELL_KNOWN && previous != null) previous.type else def.type
                val position = if (previous != null) def.position else def.position.takeIf { it >= 0 } ?: existing.size
                val next =
                    def
                        .copy(
                            key = key,
                            label = label,
                            type = type,
                            position = position,
                            hint =
                                def.hint?.trim()?.ifEmpty {
                                    null
                                },
                        )
                if (next == previous) emptyList() else listOf(PendingOp.fieldDefSet(next))
            }
        }

    override suspend fun remove(
        key: String,
        clearValues: Boolean,
    ) = owned {
        coordinator.commitBuilt { state ->
            val ops = ArrayList<PendingOp>()
            if (state.effectiveFieldDef(key) != null) ops += PendingOp.fieldDefRemove(key)
            if (clearValues) state.docsCarrying(key).forEach { ops += PendingOp.fieldSet(it, key, null) }
            ops
        }
    }

    /**
     * Every document that shows a value for [key] in this state, by its current id: the journal's
     * own list, so a trashed document or one this device has no row for is among them.
     */
    private fun FoldedState.docsCarrying(key: String): List<String> =
        docsWithMetadata().map(::resolveDocId).distinct().filter { key in inheritedFields(it) }

    override suspend fun reorder(keys: List<String>) =
        owned {
            coordinator.commitBuilt { state ->
                val byKey = state.effectiveFieldDefs().associateBy { it.key }
                val ordered = keys.mapNotNull { byKey[it] } + byKey.values.filter { it.key !in keys }
                ordered.mapIndexedNotNull { index, def ->
                    if (def.position == index) null else PendingOp.fieldDefSet(def.copy(position = index))
                }
            }
        }
}
