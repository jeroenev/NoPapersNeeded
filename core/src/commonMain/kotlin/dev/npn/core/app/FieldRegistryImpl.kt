package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.defs
import dev.npn.core.db.toFieldDef
import dev.npn.core.index.JournalCoordinator
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
 * and the write it guards cannot be split by a concurrent commit. Writes run through [SessionLifetime.owned], so
 * the session waits them out before it closes.
 */
internal class FieldRegistryImpl(
    private val db: NpnDatabase,
    private val coordinator: JournalCoordinator,
    private val lifetime: SessionLifetime,
) : FieldRegistry {
    private val defDao get() = db.fieldDefDao()
    private val fieldDao get() = db.documentFieldDao()

    // The app's own fields ([FieldKeys.HIDDEN]) are left out of every read: no list offers them.
    override val fields: Flow<List<FieldDef>> =
        defDao
            .observeAll()
            .map { rows -> rows.mapNotNull { it.toFieldDef() }.filter { it.key !in FieldKeys.HIDDEN } }
            .distinctUntilChanged()

    override fun orphanKeys(): Flow<List<String>> =
        defDao.observeOrphanKeys().map { keys -> keys.filter { it !in FieldKeys.HIDDEN } }

    override fun usageCounts(): Flow<Map<String, Int>> =
        fieldDao.observeKeysWithCounts().map { rows ->
            rows.filter { it.fieldKey !in FieldKeys.HIDDEN }.associate { it.fieldKey to it.count }
        }

    override fun distinctValues(
        key: String,
        limit: Int,
    ): Flow<List<String>> = fieldDao.observeDistinctValues(key, limit)

    override suspend fun current(): List<FieldDef> = defDao.defs().filter { it.key !in FieldKeys.HIDDEN }

    override suspend fun upsert(def: FieldDef) =
        lifetime.owned<Unit> {
            val key = FieldKeys.normalize(def.key) ?: throw IllegalArgumentException("Field key is empty")
            val label = def.label.trim().ifEmpty { key }
            coordinator.commitBuilt { state ->
                val existing = state.effectiveFieldDefs()
                val collision = existing.firstOrNull { it.key != key && FieldKeys.sameKey(it.key, key) }
                require(collision == null) { "A field with key '${collision?.key}' already exists" }
                val previous = state.effectiveFieldDef(key)
                // Well-known keys keep their seeded type: templates like {docDate:yyyy} rely on it.
                val type = if (key in FieldKeys.WELL_KNOWN && previous != null) previous.type else def.type
                val position =
                    when {
                        previous != null || def.position >= 0 -> def.position

                        // After the last one, whatever gaps removals left in the positions.
                        else -> existing.maxOfOrNull { it.position }?.plus(1) ?: 0
                    }
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
    ) = lifetime.owned<Unit> {
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

    override suspend fun move(
        key: String,
        delta: Int,
    ) = commitOrder { all ->
        // Among the fields the registry lists; the app's own stay after them, out of every move.
        val (hidden, defs) = all.partition { it.key in FieldKeys.HIDDEN }
        val from = defs.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in defs.indices) null else defs.toMutableList().apply { add(to, removeAt(from)) } + hidden
    }

    /**
     * Number the registry 0, 1, 2... in the order [order] makes of it, under the coordinator lock so
     * the order is computed from the registry it rewrites. [order] answering null changes nothing.
     */
    private suspend fun commitOrder(order: (List<FieldDef>) -> List<FieldDef>?) =
        lifetime.owned<Unit> {
            coordinator.commitBuilt { state ->
                order(state.effectiveFieldDefs()).orEmpty().mapIndexedNotNull { index, def ->
                    if (def.position == index) null else PendingOp.fieldDefSet(def.copy(position = index))
                }
            }
        }
}
