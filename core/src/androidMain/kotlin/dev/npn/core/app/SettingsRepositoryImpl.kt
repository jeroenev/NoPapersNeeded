package dev.npn.core.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

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
        val installationId: String,
        val watchedFolder: String?,
        val watchedFolderDeleteAfterImport: Boolean,
        val storagePathTemplate: String?,
        val storagePathEnabled: Boolean,
        val autoMatchRulesJson: String,
        val aiEnabled: Boolean,
        val aiBaseUrl: String,
        val aiApiKey: String,
        val aiModel: String,
        val aiMaxTagsPerDoc: Int,
        val aiMaxNewTagsPerDoc: Int,
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
        installationId = prefs.getString(KEY_INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALLATION_ID, it).apply()
        },
        watchedFolder = prefs.getString(KEY_WATCHED_FOLDER, null)?.ifEmpty { null },
        watchedFolderDeleteAfterImport = prefs.getBoolean(KEY_WATCHED_DELETE, false),
        storagePathTemplate = prefs.getString(KEY_STORAGE_TEMPLATE, null)?.ifEmpty { null },
        storagePathEnabled = prefs.getBoolean(KEY_STORAGE_ENABLED, false),
        autoMatchRulesJson = prefs.getString(KEY_AUTO_MATCH_RULES, "[]") ?: "[]",
        aiEnabled = prefs.getBoolean(KEY_AI_ENABLED, false),
        aiBaseUrl = prefs.getString(KEY_AI_BASE_URL, "") ?: "",
        aiApiKey = prefs.getString(KEY_AI_API_KEY, "") ?: "",
        aiModel = prefs.getString(KEY_AI_MODEL, "") ?: "",
        aiMaxTagsPerDoc = prefs.getInt(KEY_AI_MAX_TAGS, AiTagging.DEFAULT_TAGS_PER_DOC)
            .coerceIn(AiTagging.RANGE),
        aiMaxNewTagsPerDoc = prefs.getInt(KEY_AI_MAX_NEW_TAGS, AiTagging.DEFAULT_NEW_TAGS_PER_DOC)
            .coerceIn(AiTagging.NEW_TAGS_RANGE),
    )

    override val libraryLocation: Flow<String?> = state.map { it.libraryLocation }
    override val ocrLanguage: Flow<String> = state.map { it.ocrLanguage }
    override val trashRetentionDays: Flow<Int> = state.map { it.trashRetentionDays }
    override val defaultViewType: Flow<ViewType> = state.map { it.defaultViewType }
    override val librarySort: Flow<LibrarySort> = state.map { it.librarySort }
    override val gridColumnWidth: Flow<Int> = state.map { it.gridColumnWidth }
    override val landscapeListColumns: Flow<Boolean> = state.map { it.landscapeListColumns }
    override val installationId: Flow<String> = state.map { it.installationId }
    override val watchedFolder: Flow<String?> = state.map { it.watchedFolder }
    override val watchedFolderDeleteAfterImport: Flow<Boolean> =
        state.map { it.watchedFolderDeleteAfterImport }
    override val storagePathTemplate: Flow<String?> = state.map { it.storagePathTemplate }
    override val storagePathEnabled: Flow<Boolean> = state.map { it.storagePathEnabled }
    override val autoMatchRulesJson: Flow<String> = state.map { it.autoMatchRulesJson }
    override val aiEnabled: Flow<Boolean> = state.map { it.aiEnabled }
    override val aiBaseUrl: Flow<String> = state.map { it.aiBaseUrl }
    override val aiApiKey: Flow<String> = state.map { it.aiApiKey }
    override val aiModel: Flow<String> = state.map { it.aiModel }
    override val aiMaxTagsPerDoc: Flow<Int> = state.map { it.aiMaxTagsPerDoc }
    override val aiMaxNewTagsPerDoc: Flow<Int> = state.map { it.aiMaxNewTagsPerDoc }

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

    override suspend fun setWatchedFolder(location: String?) {
        prefs.edit().apply {
            if (location.isNullOrEmpty()) remove(KEY_WATCHED_FOLDER) else putString(KEY_WATCHED_FOLDER, location)
        }.apply()
        state.value = load()
    }

    override suspend fun setWatchedFolderDeleteAfterImport(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WATCHED_DELETE, enabled).apply()
        state.value = load()
    }

    override suspend fun setStoragePathTemplate(template: String?) {
        prefs.edit().apply {
            if (template.isNullOrEmpty()) remove(KEY_STORAGE_TEMPLATE) else putString(KEY_STORAGE_TEMPLATE, template)
        }.apply()
        state.value = load()
    }

    override suspend fun setStoragePathEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STORAGE_ENABLED, enabled).apply()
        state.value = load()
    }

    override suspend fun setAutoMatchRulesJson(json: String) {
        prefs.edit().putString(KEY_AUTO_MATCH_RULES, json).commit()
        state.value = load()
    }

    override suspend fun setAiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_ENABLED, enabled).apply()
        state.value = load()
    }

    override suspend fun setAiBaseUrl(url: String) {
        prefs.edit().putString(KEY_AI_BASE_URL, url).apply()
        state.value = load()
    }

    override suspend fun setAiApiKey(key: String) {
        prefs.edit().putString(KEY_AI_API_KEY, key).apply()
        state.value = load()
    }

    override suspend fun setAiModel(model: String) {
        prefs.edit().putString(KEY_AI_MODEL, model).apply()
        state.value = load()
    }

    override suspend fun setAiMaxTagsPerDoc(value: Int) {
        prefs.edit().putInt(KEY_AI_MAX_TAGS, value.coerceIn(AiTagging.RANGE)).apply()
        state.value = load()
    }

    override suspend fun setAiMaxNewTagsPerDoc(value: Int) {
        prefs.edit().putInt(KEY_AI_MAX_NEW_TAGS, value.coerceIn(AiTagging.NEW_TAGS_RANGE)).apply()
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
        private const val KEY_INSTALLATION_ID = "installationId"
        private const val KEY_WATCHED_FOLDER = "watchedFolder"
        private const val KEY_WATCHED_DELETE = "watchedFolderDeleteAfterImport"
        private const val KEY_STORAGE_TEMPLATE = "storagePathTemplate"
        private const val KEY_STORAGE_ENABLED = "storagePathEnabled"
        private const val KEY_AUTO_MATCH_RULES = "autoMatchRulesJson"
        private const val KEY_AI_ENABLED = "aiEnabled"
        private const val KEY_AI_BASE_URL = "aiBaseUrl"
        private const val KEY_AI_API_KEY = "aiApiKey"
        private const val KEY_AI_MODEL = "aiModel"
        private const val KEY_AI_MAX_TAGS = "aiMaxTagsPerDoc"
        private const val KEY_AI_MAX_NEW_TAGS = "aiMaxNewTagsPerDoc"
    }
}
