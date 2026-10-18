package dev.npn.core.app

import dev.npn.core.db.LegacyIndexTables
import dev.npn.core.db.NpnDatabase
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.util.NpnJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Saved views are library data: each is one synced setting in the journal, keyed by its id and
 * holding the whole [SavedView], so every device sees the same views and two devices saving
 * different views both keep theirs. Deleting one clears its setting. They read in name order.
 * A view this build cannot decode (a newer build's) is not listed: a rename or a pin saves the view
 * as this build read it, and would write over what that build meant.
 * Writes run through [SessionLifetime.owned], so the session waits them out before it closes.
 */
internal class SavedViewRepositoryImpl(
    private val coordinator: JournalCoordinator,
    private val lifetime: SessionLifetime,
) : SavedViewRepository {
    private val json = NpnJson.Persisted

    override fun observeSavedViews(): Flow<List<SavedView>> =
        coordinator
            .settings()
            .map { settings ->
                settings.filterKeys { it.startsWith(LibrarySettingKeys.SAVED_VIEW_PREFIX) }.values.mapNotNull(::decode)
            }.distinctUntilChanged()
            .map { views -> views.sortedWith(compareBy(SavedView::name, SavedView::id)) }

    /** Save [view], writing nothing when the journal already holds it as it is. */
    override suspend fun save(view: SavedView) =
        lifetime.owned<Unit> { coordinator.putSetting(key(view.id), encode(view)) }

    override suspend fun delete(id: String) = lifetime.owned<Unit> { coordinator.putSetting(key(id), null) }

    /**
     * Move the views older builds kept in the index into the journal, then drop their table. A view
     * whose id the journal already holds, deleted ones included, stays as the journal has it.
     */
    internal suspend fun adoptIndexViews(db: NpnDatabase) {
        val stored = LegacyIndexTables.savedViews(db) ?: return
        val views = stored.mapNotNull(::decode).associate { key(it.id) to encode(it) }
        val adopted = coordinator.adoptSettings(views.map { (key, view) -> mapOf(key to view) })
        if (adopted) LegacyIndexTables.drop(db, LegacyIndexTables.SAVED_VIEWS)
    }

    private fun encode(view: SavedView): String = json.encodeToString(SavedView.serializer(), view)

    private fun decode(value: String?): SavedView? =
        value?.let {
            try {
                json.decodeFromString(SavedView.serializer(), it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private fun key(id: String): String = LibrarySettingKeys.SAVED_VIEW_PREFIX + id
}
