package dev.npn.app

import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.app.AiTagging
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.SettingsSnapshot
import dev.npn.core.app.ViewType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/** Settings fake backed by one snapshot flow; setters both record and update the snapshot. */
class FakeSettingsRepository(
    initialDefaultView: ViewType? = ViewType.LIST,
) : SettingsRepository {
    val settingsFlow =
        MutableStateFlow(
            initialDefaultView?.let {
                SettingsSnapshot(installationId = "test-installation", defaultViewType = it)
            },
        )

    override val settings: Flow<SettingsSnapshot> = settingsFlow.filterNotNull()

    fun updateSettings(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        settingsFlow.value = transform(settingsFlow.value ?: defaultSnapshot())
    }

    val setDefaultViews = mutableListOf<ViewType>()
    val setSorts = mutableListOf<LibrarySort>()
    val setOcrLanguages = mutableListOf<String>()
    val setRetentionDays = mutableListOf<Int>()
    val setGridWidths = mutableListOf<Int>()
    val setLandscapeFlags = mutableListOf<Boolean>()
    val setStoragePathTemplates = mutableListOf<String?>()
    val setStoragePathEnabledFlags = mutableListOf<Boolean>()
    val setAppLanguages = mutableListOf<String?>()

    override suspend fun setLibraryLocation(location: String?) = updateSettings { it.copy(libraryLocation = location) }

    override suspend fun setAppLanguage(lang: String?) {
        setAppLanguages += lang
        updateSettings { it.copy(appLanguage = lang) }
    }

    override suspend fun setOcrLanguage(lang: String) {
        setOcrLanguages += lang
        updateSettings { it.copy(ocrLanguage = lang) }
    }

    override suspend fun setTrashRetentionDays(days: Int) {
        setRetentionDays += days
        updateSettings { it.copy(trashRetentionDays = days) }
    }

    fun emitDefaultView(viewType: ViewType) = updateSettings { it.copy(defaultViewType = viewType) }

    override suspend fun setDefaultViewType(v: ViewType) {
        setDefaultViews += v
        updateSettings { it.copy(defaultViewType = v) }
    }

    override suspend fun setLibrarySort(sort: LibrarySort) {
        setSorts += sort
        updateSettings { it.copy(librarySort = sort) }
    }

    override suspend fun setGridColumnWidth(dp: Int) {
        setGridWidths += dp
        updateSettings { it.copy(gridColumnWidth = dp) }
    }

    override suspend fun setLandscapeListColumns(enabled: Boolean) {
        setLandscapeFlags += enabled
        updateSettings { it.copy(landscapeListColumns = enabled) }
    }

    override suspend fun setStoragePathTemplate(template: String?) {
        setStoragePathTemplates += template
        updateSettings { it.copy(storagePathTemplate = template) }
    }

    override suspend fun setStoragePathEnabled(enabled: Boolean) {
        setStoragePathEnabledFlags += enabled
        updateSettings { it.copy(storagePathEnabled = enabled) }
    }

    override suspend fun setAutoMatchRulesJson(json: String) = updateSettings { it.copy(autoMatchRulesJson = json) }

    val setAiMaxTagsPerDocValues = mutableListOf<Int>()
    val setAiMaxNewTagsPerDocValues = mutableListOf<Int>()

    override suspend fun setAiEnabled(enabled: Boolean) = updateSettings { it.copy(aiEnabled = enabled) }

    override suspend fun setAiProvider(provider: AiProvider) = updateSettings { it.copy(aiProvider = provider) }

    override suspend fun setAiBaseUrl(url: String) = updateSettings { it.copy(aiBaseUrl = url) }

    override suspend fun setAiApiKey(key: String) = updateSettings { it.copy(aiApiKey = key) }

    override suspend fun setAiModel(model: String) = updateSettings { it.copy(aiModel = model) }

    override suspend fun setAiReasoningAssistant(level: AiReasoningLevel) =
        updateSettings { it.copy(aiReasoningAssistant = level) }

    override suspend fun setAiReasoningEdit(level: AiReasoningLevel) =
        updateSettings { it.copy(aiReasoningEdit = level) }

    override suspend fun setAiMaxTagsPerDoc(value: Int) {
        setAiMaxTagsPerDocValues += value
        updateSettings { it.copy(aiMaxTagsPerDoc = value.coerceIn(AiTagging.RANGE)) }
    }

    override suspend fun setAiMaxNewTagsPerDoc(value: Int) {
        setAiMaxNewTagsPerDocValues += value
        updateSettings { it.copy(aiMaxNewTagsPerDoc = value.coerceIn(AiTagging.NEW_TAGS_RANGE)) }
    }

    override suspend fun setAiAutoOnOcr(enabled: Boolean) = updateSettings { it.copy(aiAutoOnOcr = enabled) }

    val setWatchedFolders = mutableListOf<String?>()
    val setWatchedDeleteFlags = mutableListOf<Boolean>()

    override suspend fun setWatchedFolder(location: String?) {
        setWatchedFolders += location
        updateSettings { it.copy(watchedFolder = location) }
    }

    override suspend fun setWatchedFolderDeleteAfterImport(enabled: Boolean) {
        setWatchedDeleteFlags += enabled
        updateSettings { it.copy(watchedFolderDeleteAfterImport = enabled) }
    }

    private fun defaultSnapshot() =
        SettingsSnapshot(
            installationId = "test-installation",
            defaultViewType = ViewType.LIST,
        )
}
