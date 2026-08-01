package dev.npn.core.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Android [SettingsRepository] backed by [SharedPreferences]. Values are mirrored into a
 * [MutableStateFlow] loaded at init and kept in sync via an [OnSharedPreferenceChangeListener].
 *
 * SharedPreferences avoids the extra DataStore dependency
 * and okio path handling, and these are a handful of small app-global settings.
 */
class SettingsRepositoryImpl(
    context: Context,
) : SettingsRepository {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(load())

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            state.value = load()
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun load(): SettingsSnapshot =
        SettingsSnapshot(
            installationId =
                prefs.getString(SettingsKeys.INSTALLATION_ID, null)
                    ?: UUID.randomUUID().toString().also {
                        prefs.edit().putString(SettingsKeys.INSTALLATION_ID, it).apply()
                    },
            libraryLocation = prefs.getString(SettingsKeys.LIBRARY_LOCATION, null)?.ifEmpty { null },
            appLanguage = prefs.getString(SettingsKeys.APP_LANGUAGE, null)?.ifEmpty { null },
            ocrLanguage = prefs.getString(SettingsKeys.OCR_LANGUAGE, null) ?: DEFAULTS.ocrLanguage,
            trashRetentionDays =
                prefs.getInt(
                    SettingsKeys.TRASH_RETENTION_DAYS,
                    DEFAULTS.trashRetentionDays,
                ),
            defaultViewType =
                prefs
                    .getString(SettingsKeys.DEFAULT_VIEW_TYPE, null)
                    ?.let { runCatching { ViewType.valueOf(it) }.getOrNull() } ?: ViewType.GRID,
            librarySort =
                LibrarySort(
                    field =
                        prefs
                            .getString(SettingsKeys.LIBRARY_SORT_FIELD, null)
                            ?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: LibrarySort().field,
                    ascending = prefs.getBoolean(SettingsKeys.LIBRARY_SORT_ASCENDING, LibrarySort().ascending),
                ),
            gridColumnWidth =
                prefs
                    .getInt(SettingsKeys.GRID_COLUMN_WIDTH, GridSizing.DEFAULT_COLUMN_WIDTH)
                    .coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH),
            landscapeListColumns = prefs.getBoolean(SettingsKeys.LANDSCAPE_LIST_COLUMNS, false),
            watchedFolder = prefs.getString(SettingsKeys.WATCHED_FOLDER, null)?.ifEmpty { null },
            watchedFolderDeleteAfterImport = prefs.getBoolean(SettingsKeys.WATCHED_FOLDER_DELETE_AFTER_IMPORT, false),
            storagePathTemplate = prefs.getString(SettingsKeys.STORAGE_PATH_TEMPLATE, null)?.ifEmpty { null },
            storagePathEnabled = prefs.getBoolean(SettingsKeys.STORAGE_PATH_ENABLED, false),
            autoMatchRulesJson = prefs.getString(SettingsKeys.AUTO_MATCH_RULES_JSON, null) ?: "[]",
            aiEnabled = prefs.getBoolean(SettingsKeys.AI_ENABLED, false),
            aiBaseUrl = prefs.getString(SettingsKeys.AI_BASE_URL, null) ?: "",
            aiApiKey = prefs.getString(SettingsKeys.AI_API_KEY, null) ?: "",
            aiModel = prefs.getString(SettingsKeys.AI_MODEL, null) ?: "",
            aiMaxTagsPerDoc =
                prefs
                    .getInt(SettingsKeys.AI_MAX_TAGS_PER_DOC, AiTagging.DEFAULT_TAGS_PER_DOC)
                    .coerceIn(AiTagging.RANGE),
            aiMaxNewTagsPerDoc =
                prefs
                    .getInt(SettingsKeys.AI_MAX_NEW_TAGS_PER_DOC, AiTagging.DEFAULT_NEW_TAGS_PER_DOC)
                    .coerceIn(AiTagging.NEW_TAGS_RANGE),
        )

    override val settings: Flow<SettingsSnapshot> = state

    override suspend fun setLibraryLocation(location: String?) {
        prefs
            .edit()
            .apply {
                if (location.isNullOrEmpty()) {
                    remove(SettingsKeys.LIBRARY_LOCATION)
                } else {
                    putString(SettingsKeys.LIBRARY_LOCATION, location)
                }
            }.apply()
        state.value = load()
    }

    override suspend fun setOcrLanguage(lang: String) {
        prefs.edit().putString(SettingsKeys.OCR_LANGUAGE, lang).apply()
        state.value = load()
    }

    override suspend fun setAppLanguage(lang: String?) {
        prefs
            .edit()
            .apply {
                if (lang.isNullOrEmpty()) {
                    remove(SettingsKeys.APP_LANGUAGE)
                } else {
                    putString(SettingsKeys.APP_LANGUAGE, lang)
                }
            }.apply()
        state.value = load()
    }

    override suspend fun setTrashRetentionDays(days: Int) {
        prefs.edit().putInt(SettingsKeys.TRASH_RETENTION_DAYS, days).apply()
        state.value = load()
    }

    override suspend fun setDefaultViewType(v: ViewType) {
        prefs.edit().putString(SettingsKeys.DEFAULT_VIEW_TYPE, v.name).apply()
        state.value = load()
    }

    override suspend fun setLibrarySort(sort: LibrarySort) {
        prefs
            .edit()
            .putString(SettingsKeys.LIBRARY_SORT_FIELD, sort.field.name)
            .putBoolean(SettingsKeys.LIBRARY_SORT_ASCENDING, sort.ascending)
            .apply()
        state.value = load()
    }

    override suspend fun setGridColumnWidth(dp: Int) {
        val clamped = dp.coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH)
        prefs.edit().putInt(SettingsKeys.GRID_COLUMN_WIDTH, clamped).apply()
        state.value = load()
    }

    override suspend fun setLandscapeListColumns(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.LANDSCAPE_LIST_COLUMNS, enabled).apply()
        state.value = load()
    }

    override suspend fun setWatchedFolder(location: String?) {
        prefs
            .edit()
            .apply {
                if (location.isNullOrEmpty()) {
                    remove(SettingsKeys.WATCHED_FOLDER)
                } else {
                    putString(SettingsKeys.WATCHED_FOLDER, location)
                }
            }.apply()
        state.value = load()
    }

    override suspend fun setWatchedFolderDeleteAfterImport(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.WATCHED_FOLDER_DELETE_AFTER_IMPORT, enabled).apply()
        state.value = load()
    }

    override suspend fun setStoragePathTemplate(template: String?) {
        prefs
            .edit()
            .apply {
                if (template.isNullOrEmpty()) {
                    remove(SettingsKeys.STORAGE_PATH_TEMPLATE)
                } else {
                    putString(SettingsKeys.STORAGE_PATH_TEMPLATE, template)
                }
            }.apply()
        state.value = load()
    }

    override suspend fun setStoragePathEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.STORAGE_PATH_ENABLED, enabled).apply()
        state.value = load()
    }

    override suspend fun setAutoMatchRulesJson(json: String) {
        prefs.edit().putString(SettingsKeys.AUTO_MATCH_RULES_JSON, json).commit()
        state.value = load()
    }

    override suspend fun setAiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsKeys.AI_ENABLED, enabled).apply()
        state.value = load()
    }

    override suspend fun setAiBaseUrl(url: String) {
        prefs.edit().putString(SettingsKeys.AI_BASE_URL, url).apply()
        state.value = load()
    }

    override suspend fun setAiApiKey(key: String) {
        prefs.edit().putString(SettingsKeys.AI_API_KEY, key).apply()
        state.value = load()
    }

    override suspend fun setAiModel(model: String) {
        prefs.edit().putString(SettingsKeys.AI_MODEL, model).apply()
        state.value = load()
    }

    override suspend fun setAiMaxTagsPerDoc(value: Int) {
        prefs.edit().putInt(SettingsKeys.AI_MAX_TAGS_PER_DOC, value.coerceIn(AiTagging.RANGE)).apply()
        state.value = load()
    }

    override suspend fun setAiMaxNewTagsPerDoc(value: Int) {
        prefs.edit().putInt(SettingsKeys.AI_MAX_NEW_TAGS_PER_DOC, value.coerceIn(AiTagging.NEW_TAGS_RANGE)).apply()
        state.value = load()
    }

    companion object {
        private const val PREFS_NAME = "npn_settings"
        private val DEFAULTS = SettingsSnapshot(installationId = "")
    }
}
