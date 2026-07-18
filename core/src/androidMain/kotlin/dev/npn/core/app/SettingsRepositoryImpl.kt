package dev.npn.core.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Android [SettingsRepository] backed by [SharedPreferences]. Values are mirrored into a
 * [MutableStateFlow] loaded at init and kept in sync via an [OnSharedPreferenceChangeListener].
 *
 * DataStore is intentionally not used (task note): SharedPreferences avoids the extra dependency
 * and okio path handling, and these are a handful of small app-global settings.
 */
class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(load())

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        state.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private data class Snapshot(
        val libraryLocation: String?,
        val ocrLanguage: String,
        val trashRetentionDays: Int,
        val defaultViewType: ViewType,
        val librarySort: LibrarySort,
        val gridColumnWidth: Int,
        val landscapeListColumns: Boolean,
    )

    private fun load() = Snapshot(
        libraryLocation = prefs.getString(KEY_LOCATION, null)?.ifEmpty { null },
        ocrLanguage = prefs.getString(KEY_OCR_LANG, "eng") ?: "eng",
        trashRetentionDays = prefs.getInt(KEY_TRASH_DAYS, 30),
        defaultViewType = prefs.getString(KEY_VIEW_TYPE, null)
            ?.let { runCatching { ViewType.valueOf(it) }.getOrNull() } ?: ViewType.GRID,
        librarySort = LibrarySort(
            field = prefs.getString(KEY_SORT_FIELD, null)
                ?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: LibrarySort().field,
            ascending = prefs.getBoolean(KEY_SORT_ASC, LibrarySort().ascending),
        ),
        gridColumnWidth = prefs.getInt(KEY_GRID_WIDTH, GridSizing.DEFAULT_COLUMN_WIDTH)
            .coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH),
        landscapeListColumns = prefs.getBoolean(KEY_LANDSCAPE_LIST, false),
    )

    override val libraryLocation: Flow<String?> = state.map { it.libraryLocation }
    override val ocrLanguage: Flow<String> = state.map { it.ocrLanguage }
    override val trashRetentionDays: Flow<Int> = state.map { it.trashRetentionDays }
    override val defaultViewType: Flow<ViewType> = state.map { it.defaultViewType }
    override val librarySort: Flow<LibrarySort> = state.map { it.librarySort }
    override val gridColumnWidth: Flow<Int> = state.map { it.gridColumnWidth }
    override val landscapeListColumns: Flow<Boolean> = state.map { it.landscapeListColumns }

    override suspend fun setLibraryLocation(location: String?) {
        prefs.edit().apply {
            if (location.isNullOrEmpty()) remove(KEY_LOCATION) else putString(KEY_LOCATION, location)
        }.apply()
        state.value = load()
    }

    override suspend fun setOcrLanguage(lang: String) {
        prefs.edit().putString(KEY_OCR_LANG, lang).apply()
        state.value = load()
    }

    override suspend fun setTrashRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_TRASH_DAYS, days).apply()
        state.value = load()
    }

    override suspend fun setDefaultViewType(v: ViewType) {
        prefs.edit().putString(KEY_VIEW_TYPE, v.name).apply()
        state.value = load()
    }

    override suspend fun setLibrarySort(sort: LibrarySort) {
        prefs.edit()
            .putString(KEY_SORT_FIELD, sort.field.name)
            .putBoolean(KEY_SORT_ASC, sort.ascending)
            .apply()
        state.value = load()
    }

    override suspend fun setGridColumnWidth(dp: Int) {
        val clamped = dp.coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH)
        prefs.edit().putInt(KEY_GRID_WIDTH, clamped).apply()
        state.value = load()
    }

    override suspend fun setLandscapeListColumns(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LANDSCAPE_LIST, enabled).apply()
        state.value = load()
    }

    companion object {
        private const val PREFS_NAME = "npn_settings"
        private const val KEY_LOCATION = "libraryLocation"
        private const val KEY_OCR_LANG = "ocrLanguage"
        private const val KEY_TRASH_DAYS = "trashRetentionDays"
        private const val KEY_VIEW_TYPE = "defaultViewType"
        private const val KEY_SORT_FIELD = "librarySortField"
        private const val KEY_SORT_ASC = "librarySortAscending"
        private const val KEY_GRID_WIDTH = "gridColumnWidth"
        private const val KEY_LANDSCAPE_LIST = "landscapeListColumns"
    }
}
