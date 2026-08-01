package dev.npn.core.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.UUID

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
    private val persistMutex = Mutex()

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
    )

    private fun boolProp(key: String): Boolean = props.getProperty(key)?.toBoolean() ?: false

    private fun textProp(key: String): String = props.getProperty(key) ?: ""

    private fun load(): Snapshot {
        if (file.exists()) {
            restrictFilePermissions()
            runCatching { file.inputStream().use { props.load(it) } }
        }
        val installationId = props.getProperty(KEY_INSTALLATION_ID) ?: UUID.randomUUID().toString().also { fresh ->
            props.setProperty(KEY_INSTALLATION_ID, fresh)
            runCatching {
                storeProperties()
            }
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
            landscapeListColumns = boolProp(KEY_LANDSCAPE_LIST),
            installationId = installationId,
            watchedFolder = props.getProperty(KEY_WATCHED_FOLDER)?.ifEmpty { null },
            watchedFolderDeleteAfterImport = boolProp(KEY_WATCHED_DELETE),
            storagePathTemplate = props.getProperty(KEY_STORAGE_TEMPLATE)?.ifEmpty { null },
            storagePathEnabled = boolProp(KEY_STORAGE_ENABLED),
            autoMatchRulesJson = props.getProperty(KEY_AUTO_MATCH_RULES) ?: "[]",
            aiEnabled = boolProp(KEY_AI_ENABLED),
            aiBaseUrl = textProp(KEY_AI_BASE_URL),
            aiApiKey = textProp(KEY_AI_API_KEY),
            aiModel = textProp(KEY_AI_MODEL),
            aiMaxTagsPerDoc = props.getProperty(KEY_AI_MAX_TAGS)?.toIntOrNull()
                ?.coerceIn(AiTagging.RANGE) ?: AiTagging.DEFAULT_TAGS_PER_DOC,
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
        props.setProperty(KEY_INSTALLATION_ID, snapshot.installationId)
        if (snapshot.watchedFolder != null) props.setProperty(KEY_WATCHED_FOLDER, snapshot.watchedFolder)
        else props.remove(KEY_WATCHED_FOLDER)
        props.setProperty(KEY_WATCHED_DELETE, snapshot.watchedFolderDeleteAfterImport.toString())
        if (snapshot.storagePathTemplate != null) props.setProperty(KEY_STORAGE_TEMPLATE, snapshot.storagePathTemplate)
        else props.remove(KEY_STORAGE_TEMPLATE)
        props.setProperty(KEY_STORAGE_ENABLED, snapshot.storagePathEnabled.toString())
        props.setProperty(KEY_AUTO_MATCH_RULES, snapshot.autoMatchRulesJson)
        props.setProperty(KEY_AI_ENABLED, snapshot.aiEnabled.toString())
        props.setProperty(KEY_AI_BASE_URL, snapshot.aiBaseUrl)
        props.setProperty(KEY_AI_API_KEY, snapshot.aiApiKey)
        props.setProperty(KEY_AI_MODEL, snapshot.aiModel)
        props.setProperty(KEY_AI_MAX_TAGS, snapshot.aiMaxTagsPerDoc.toString())
        storeProperties()
    }

    private fun storeProperties() {
        file.parentFile?.mkdirs()
        try {
            Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS))
        } catch (_: FileAlreadyExistsException) {
            // Existing files are tightened below before they are opened for writing.
        }
        restrictFilePermissions()
        file.outputStream().use { props.store(it, "no-papers-needed settings") }
    }

    private fun restrictFilePermissions() {
        Files.setPosixFilePermissions(file.toPath(), OWNER_ONLY_PERMISSIONS)
    }

    private suspend fun update(transform: (Snapshot) -> Snapshot) = persistMutex.withLock {
        val next = transform(state.value)
        state.value = next
        persist(next)
    }

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

    override suspend fun setLibraryLocation(location: String?) {
        update { it.copy(libraryLocation = location?.ifEmpty { null }) }
    }

    override suspend fun setOcrLanguage(lang: String) {
        update { it.copy(ocrLanguage = lang) }
    }

    override suspend fun setTrashRetentionDays(days: Int) {
        update { it.copy(trashRetentionDays = days) }
    }

    override suspend fun setDefaultViewType(v: ViewType) {
        update { it.copy(defaultViewType = v) }
    }

    override suspend fun setLibrarySort(sort: LibrarySort) {
        update { it.copy(librarySort = sort) }
    }

    override suspend fun setGridColumnWidth(dp: Int) {
        update { it.copy(
            gridColumnWidth = dp.coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH),
        ) }
    }

    override suspend fun setLandscapeListColumns(enabled: Boolean) {
        update { it.copy(landscapeListColumns = enabled) }
    }

    override suspend fun setWatchedFolder(location: String?) {
        update { it.copy(watchedFolder = location?.ifEmpty { null }) }
    }

    override suspend fun setWatchedFolderDeleteAfterImport(enabled: Boolean) {
        update { it.copy(watchedFolderDeleteAfterImport = enabled) }
    }

    override suspend fun setStoragePathTemplate(template: String?) {
        update { it.copy(storagePathTemplate = template?.ifEmpty { null }) }
    }

    override suspend fun setStoragePathEnabled(enabled: Boolean) {
        update { it.copy(storagePathEnabled = enabled) }
    }

    override suspend fun setAutoMatchRulesJson(json: String) {
        update { it.copy(autoMatchRulesJson = json) }
    }

    override suspend fun setAiEnabled(enabled: Boolean) {
        update { it.copy(aiEnabled = enabled) }
    }

    override suspend fun setAiBaseUrl(url: String) {
        update { it.copy(aiBaseUrl = url) }
    }

    override suspend fun setAiApiKey(key: String) {
        update { it.copy(aiApiKey = key) }
    }

    override suspend fun setAiModel(model: String) {
        update { it.copy(aiModel = model) }
    }

    override suspend fun setAiMaxTagsPerDoc(value: Int) {
        update { it.copy(aiMaxTagsPerDoc = value.coerceIn(AiTagging.RANGE)) }
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
        private val OWNER_ONLY_PERMISSIONS = PosixFilePermissions.fromString("rw-------")

        fun defaultFile(): File {
            val configHome = System.getenv("XDG_CONFIG_HOME")?.ifEmpty { null }
                ?: (System.getProperty("user.home") + "/.config")
            return File("$configHome/no-papers-needed/settings.properties")
        }
    }
}
