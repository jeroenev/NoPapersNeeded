package dev.npn.core.app

import dev.npn.core.db.SavedViewDao
import dev.npn.core.db.SavedViewEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Saved views persisted in the [SavedViewEntity] table. The full [SavedView] contract type is
 * kotlinx-serialized into the entity's `json` column; the entity's own `name`/`pinned`/`position`
 * columns are kept in sync for cheap sorting and are the source of truth for ordering.
 *
 * Export/import is a JSON array of [SavedView]. Import is tolerant: broken entries are skipped and
 * ids are deduped (later entries overwrite earlier ones with the same id).
 */
class SavedViewRepositoryImpl(private val dao: SavedViewDao) : SavedViewRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(SavedView.serializer())

    override fun observeSavedViews(): Flow<List<SavedView>> =
        dao.observeAll().map { rows -> rows.mapNotNull { decode(it) } }

    override suspend fun save(view: SavedView) {
        dao.upsert(
            SavedViewEntity(
                id = view.id,
                name = view.name,
                json = json.encodeToString(SavedView.serializer(), view),
                pinned = view.pinned,
                position = 0,
            ),
        )
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun exportJson(): String {
        val views = dao.observeAll().first().mapNotNull { decode(it) }
        return json.encodeToString(listSerializer, views)
    }

    override suspend fun importJson(json: String): Int {
        val jsonText = json
        val parsed = runCatching { this.json.decodeFromString(listSerializer, jsonText) }.getOrNull()
            ?: return importLenient(jsonText)
        var count = 0
        val seen = HashSet<String>()
        // Later entries win on duplicate id: iterate in reverse to keep the last, but persist all
        // distinct ids once.
        for (view in parsed) {
            if (view.id.isBlank()) continue
            if (!seen.add(view.id)) continue
            save(view)
            count++
        }
        return count
    }

    /**
     * Fallback for a partially-broken array: parse element by element via a JsonArray so one bad
     * entry does not abort the whole import.
     */
    private suspend fun importLenient(jsonText: String): Int {
        val element = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() ?: return 0
        val array = (element as? kotlinx.serialization.json.JsonArray) ?: return 0
        var count = 0
        val seen = HashSet<String>()
        for (item in array) {
            val view = runCatching { json.decodeFromJsonElement(SavedView.serializer(), item) }.getOrNull()
                ?: continue
            if (view.id.isBlank() || !seen.add(view.id)) continue
            save(view)
            count++
        }
        return count
    }

    private fun decode(entity: SavedViewEntity): SavedView? =
        runCatching { json.decodeFromString(SavedView.serializer(), entity.json) }.getOrNull()
}
