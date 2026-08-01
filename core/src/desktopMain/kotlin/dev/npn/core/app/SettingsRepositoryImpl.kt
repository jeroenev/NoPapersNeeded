package dev.npn.core.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * A plain properties file sidesteps okio/path
 * fiddling on desktop and is enough for a handful of app-global settings.
 */
class SettingsRepositoryImpl(
    private val file: File = defaultFile(),
) : SettingsRepository {
    private val props = Properties()
    private val state = MutableStateFlow(load())
    private val persistMutex = Mutex()

    private fun boolProp(key: String): Boolean = props.getProperty(key)?.toBoolean() ?: false

    private fun textProp(key: String): String = props.getProperty(key) ?: ""

    private fun load(): SettingsSnapshot {
        if (file.exists()) {
            restrictFilePermissions()
            runCatching { file.inputStream().use { props.load(it) } }
        }
        val installationId =
            props.getProperty(SettingsKeys.INSTALLATION_ID)
                ?: UUID.randomUUID().toString().also { fresh ->
                    props.setProperty(SettingsKeys.INSTALLATION_ID, fresh)
                    runCatching {
                        storeProperties()
                    }
                }
        return SettingsSnapshot(
            installationId = installationId,
            libraryLocation = props.getProperty(SettingsKeys.LIBRARY_LOCATION)?.ifEmpty { null },
            appLanguage = props.getProperty(SettingsKeys.APP_LANGUAGE)?.ifEmpty { null },
            ocrLanguage = props.getProperty(SettingsKeys.OCR_LANGUAGE) ?: "eng",
            trashRetentionDays =
                props.getProperty(SettingsKeys.TRASH_RETENTION_DAYS)?.toIntOrNull()
                    ?: TrashSettings.DEFAULT_TRASH_RETENTION_DAYS,
            defaultViewType =
                props
                    .getProperty(SettingsKeys.DEFAULT_VIEW_TYPE)
                    ?.let { runCatching { ViewType.valueOf(it) }.getOrNull() }
                    ?: ViewType.GRID,
            librarySort =
                LibrarySort(
                    field =
                        props
                            .getProperty(SettingsKeys.LIBRARY_SORT_FIELD)
                            ?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: LibrarySort().field,
                    ascending =
                        props.getProperty(SettingsKeys.LIBRARY_SORT_ASCENDING)?.toBooleanStrictOrNull()
                            ?: LibrarySort().ascending,
                ),
            gridColumnWidth =
                props
                    .getProperty(SettingsKeys.GRID_COLUMN_WIDTH)
                    ?.toIntOrNull()
                    ?.coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH)
                    ?: GridSizing.DEFAULT_COLUMN_WIDTH,
            landscapeListColumns = boolProp(SettingsKeys.LANDSCAPE_LIST_COLUMNS),
            watchedFolder = props.getProperty(SettingsKeys.WATCHED_FOLDER)?.ifEmpty { null },
            watchedFolderDeleteAfterImport = boolProp(SettingsKeys.WATCHED_FOLDER_DELETE_AFTER_IMPORT),
            storagePathTemplate = props.getProperty(SettingsKeys.STORAGE_PATH_TEMPLATE)?.ifEmpty { null },
            storagePathEnabled = boolProp(SettingsKeys.STORAGE_PATH_ENABLED),
            autoMatchRulesJson = props.getProperty(SettingsKeys.AUTO_MATCH_RULES_JSON) ?: "[]",
            aiEnabled = boolProp(SettingsKeys.AI_ENABLED),
            aiBaseUrl = textProp(SettingsKeys.AI_BASE_URL),
            aiApiKey = textProp(SettingsKeys.AI_API_KEY),
            aiModel = textProp(SettingsKeys.AI_MODEL),
            aiMaxTagsPerDoc =
                props
                    .getProperty(SettingsKeys.AI_MAX_TAGS_PER_DOC)
                    ?.toIntOrNull()
                    ?.coerceIn(AiTagging.RANGE) ?: AiTagging.DEFAULT_TAGS_PER_DOC,
            aiMaxNewTagsPerDoc =
                props
                    .getProperty(SettingsKeys.AI_MAX_NEW_TAGS_PER_DOC)
                    ?.toIntOrNull()
                    ?.coerceIn(AiTagging.NEW_TAGS_RANGE) ?: AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
        )
    }

    private suspend fun persist(snapshot: SettingsSnapshot) =
        withContext(Dispatchers.IO) {
            setOptionalProperty(SettingsKeys.LIBRARY_LOCATION, snapshot.libraryLocation)
            setOptionalProperty(SettingsKeys.APP_LANGUAGE, snapshot.appLanguage)
            props.setProperty(SettingsKeys.OCR_LANGUAGE, snapshot.ocrLanguage)
            props.setProperty(SettingsKeys.TRASH_RETENTION_DAYS, snapshot.trashRetentionDays.toString())
            props.setProperty(SettingsKeys.DEFAULT_VIEW_TYPE, snapshot.defaultViewType.name)
            props.setProperty(SettingsKeys.LIBRARY_SORT_FIELD, snapshot.librarySort.field.name)
            props.setProperty(SettingsKeys.LIBRARY_SORT_ASCENDING, snapshot.librarySort.ascending.toString())
            props.setProperty(SettingsKeys.GRID_COLUMN_WIDTH, snapshot.gridColumnWidth.toString())
            props.setProperty(SettingsKeys.LANDSCAPE_LIST_COLUMNS, snapshot.landscapeListColumns.toString())
            props.setProperty(SettingsKeys.INSTALLATION_ID, snapshot.installationId)
            setOptionalProperty(SettingsKeys.WATCHED_FOLDER, snapshot.watchedFolder)
            props.setProperty(
                SettingsKeys.WATCHED_FOLDER_DELETE_AFTER_IMPORT,
                snapshot.watchedFolderDeleteAfterImport.toString(),
            )
            setOptionalProperty(SettingsKeys.STORAGE_PATH_TEMPLATE, snapshot.storagePathTemplate)
            props.setProperty(SettingsKeys.STORAGE_PATH_ENABLED, snapshot.storagePathEnabled.toString())
            props.setProperty(SettingsKeys.AUTO_MATCH_RULES_JSON, snapshot.autoMatchRulesJson)
            props.setProperty(SettingsKeys.AI_ENABLED, snapshot.aiEnabled.toString())
            props.setProperty(SettingsKeys.AI_BASE_URL, snapshot.aiBaseUrl)
            props.setProperty(SettingsKeys.AI_API_KEY, snapshot.aiApiKey)
            props.setProperty(SettingsKeys.AI_MODEL, snapshot.aiModel)
            props.setProperty(SettingsKeys.AI_MAX_TAGS_PER_DOC, snapshot.aiMaxTagsPerDoc.toString())
            props.setProperty(SettingsKeys.AI_MAX_NEW_TAGS_PER_DOC, snapshot.aiMaxNewTagsPerDoc.toString())
            storeProperties()
        }

    private fun setOptionalProperty(
        key: String,
        value: String?,
    ) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
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

    private suspend fun update(transform: (SettingsSnapshot) -> SettingsSnapshot) =
        persistMutex.withLock {
            val next = transform(state.value)
            state.value = next
            persist(next)
        }

    override val settings: Flow<SettingsSnapshot> = state

    override suspend fun setLibraryLocation(location: String?) {
        update { it.copy(libraryLocation = location?.ifEmpty { null }) }
    }

    override suspend fun setAppLanguage(lang: String?) {
        update { it.copy(appLanguage = lang?.ifEmpty { null }) }
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
        update {
            it.copy(
                gridColumnWidth = dp.coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH),
            )
        }
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

    override suspend fun setAiMaxNewTagsPerDoc(value: Int) {
        update { it.copy(aiMaxNewTagsPerDoc = value.coerceIn(AiTagging.NEW_TAGS_RANGE)) }
    }

    companion object {
        private val OWNER_ONLY_PERMISSIONS = PosixFilePermissions.fromString("rw-------")

        fun defaultFile(): File {
            val configHome =
                System.getenv("XDG_CONFIG_HOME")?.ifEmpty { null }
                    ?: (System.getProperty("user.home") + "/.config")
            return File("$configHome/no-papers-needed/settings.properties")
        }
    }
}
