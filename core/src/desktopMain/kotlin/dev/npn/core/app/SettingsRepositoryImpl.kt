package dev.npn.core.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/**
 * Desktop [SettingsRepository] persisted to a `settings.properties` file under
 * `~/.config/no-papers-needed/`. Values are held in a [MutableStateFlow] loaded at init; writes
 * update the flow and persist synchronously on [Dispatchers.IO].
 *
 * DataStore is intentionally avoided (task note): a plain properties file sidesteps okio/path
 * fiddling on desktop and is enough for a handful of app-global settings.
 */
class SettingsRepositoryImpl(
    private val file: File = defaultFile(),
) : SettingsRepository {

    private val props = Properties()
    private val state = MutableStateFlow(load())

    private data class Snapshot(
        val libraryLocation: String?,
        val ocrLanguage: String,
        val trashRetentionDays: Int,
        val defaultViewType: ViewType,
        val librarySort: LibrarySort,
        val gridColumnWidth: Int,
        val landscapeListColumns: Boolean,
    )

    private fun load(): Snapshot {
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
        return Snapshot(
            libraryLocation = props.getProperty(KEY_LOCATION)?.ifEmpty { null },
            ocrLanguage = props.getProperty(KEY_OCR_LANG) ?: "eng",
            trashRetentionDays = props.getProperty(KEY_TRASH_DAYS)?.toIntOrNull() ?: 30,
            defaultViewType = props.getProperty(KEY_VIEW_TYPE)?.let { runCatching { ViewType.valueOf(it) }.getOrNull() }
                ?: ViewType.GRID,
            librarySort = LibrarySort(
                field = props.getProperty(KEY_SORT_FIELD)
                    ?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: LibrarySort().field,
                ascending = props.getProperty(KEY_SORT_ASC)?.toBooleanStrictOrNull()
                    ?: LibrarySort().ascending,
            ),
            gridColumnWidth = props.getProperty(KEY_GRID_WIDTH)?.toIntOrNull()
                ?.coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH)
                ?: GridSizing.DEFAULT_COLUMN_WIDTH,
            landscapeListColumns = props.getProperty(KEY_LANDSCAPE_LIST)?.toBoolean() ?: false,
        )
    }

    private suspend fun persist(snapshot: Snapshot) = withContext(Dispatchers.IO) {
        if (snapshot.libraryLocation != null) props.setProperty(KEY_LOCATION, snapshot.libraryLocation)
        else props.remove(KEY_LOCATION)
        props.setProperty(KEY_OCR_LANG, snapshot.ocrLanguage)
        props.setProperty(KEY_TRASH_DAYS, snapshot.trashRetentionDays.toString())
        props.setProperty(KEY_VIEW_TYPE, snapshot.defaultViewType.name)
        props.setProperty(KEY_SORT_FIELD, snapshot.librarySort.field.name)
        props.setProperty(KEY_SORT_ASC, snapshot.librarySort.ascending.toString())
        props.setProperty(KEY_GRID_WIDTH, snapshot.gridColumnWidth.toString())
        props.setProperty(KEY_LANDSCAPE_LIST, snapshot.landscapeListColumns.toString())
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "no-papers-needed settings") }
    }

    override val libraryLocation: Flow<String?> = state.map { it.libraryLocation }
    override val ocrLanguage: Flow<String> = state.map { it.ocrLanguage }
    override val trashRetentionDays: Flow<Int> = state.map { it.trashRetentionDays }
    override val defaultViewType: Flow<ViewType> = state.map { it.defaultViewType }
    override val librarySort: Flow<LibrarySort> = state.map { it.librarySort }
    override val gridColumnWidth: Flow<Int> = state.map { it.gridColumnWidth }
    override val landscapeListColumns: Flow<Boolean> = state.map { it.landscapeListColumns }

    override suspend fun setLibraryLocation(location: String?) {
        val next = state.value.copy(libraryLocation = location?.ifEmpty { null })
        state.value = next
        persist(next)
    }

    override suspend fun setOcrLanguage(lang: String) {
        val next = state.value.copy(ocrLanguage = lang)
        state.value = next
        persist(next)
    }

    override suspend fun setTrashRetentionDays(days: Int) {
        val next = state.value.copy(trashRetentionDays = days)
        state.value = next
        persist(next)
    }

    override suspend fun setDefaultViewType(v: ViewType) {
        val next = state.value.copy(defaultViewType = v)
        state.value = next
        persist(next)
    }

    override suspend fun setLibrarySort(sort: LibrarySort) {
        val next = state.value.copy(librarySort = sort)
        state.value = next
        persist(next)
    }

    override suspend fun setGridColumnWidth(dp: Int) {
        val next = state.value.copy(
            gridColumnWidth = dp.coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH),
        )
        state.value = next
        persist(next)
    }

    override suspend fun setLandscapeListColumns(enabled: Boolean) {
        val next = state.value.copy(landscapeListColumns = enabled)
        state.value = next
        persist(next)
    }

    companion object {
        private const val KEY_LOCATION = "libraryLocation"
        private const val KEY_OCR_LANG = "ocrLanguage"
        private const val KEY_TRASH_DAYS = "trashRetentionDays"
        private const val KEY_VIEW_TYPE = "defaultViewType"
        private const val KEY_SORT_FIELD = "librarySortField"
        private const val KEY_SORT_ASC = "librarySortAscending"
        private const val KEY_GRID_WIDTH = "gridColumnWidth"
        private const val KEY_LANDSCAPE_LIST = "landscapeListColumns"

        fun defaultFile(): File {
            val configHome = System.getenv("XDG_CONFIG_HOME")?.ifEmpty { null }
                ?: (System.getProperty("user.home") + "/.config")
            return File("$configHome/no-papers-needed/settings.properties")
        }
    }
}
