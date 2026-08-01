package dev.npn.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.app.ui.common.WhileSubscribedTimeout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val libraryLocation: String? = null,
    val ocrLanguage: String = "eng",
    val trashRetentionDays: Int = 30,
    val defaultViewType: ViewType = ViewType.GRID,
    val landscapeListColumns: Boolean = false,
    val watchedFolder: String? = null,
    val watchedFolderDeleteAfterImport: Boolean = false,
    val storagePathTemplate: String = "{doc_date:yyyy}/{title}.pdf",
    val storagePathEnabled: Boolean = false,
    val storagePathPreview: String? = null,
    val storagePathError: String? = null,
    val aiEnabled: Boolean = false,
    val aiBaseUrl: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "",
    val aiMaxTagsPerDoc: Int = 8,
)

/** Choice offered by the 1->2 (UNTAGGED_ONLY -> INBOX_WORKFLOW) migration prompt. */
enum class ReviewMigration { MARK_ALL, MARK_TAGGED, MARK_NONE }

/** Groups the four AI settings flows so they fit a single [combine] slot. */
private data class AiSettings(
    val enabled: Boolean,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val maxTagsPerDoc: Int,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val session: LibrarySession,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        combine(
            settings.libraryLocation,
            settings.ocrLanguage,
            settings.trashRetentionDays,
        ) { location, lang, retention -> Triple(location, lang, retention) },
        combine(
            settings.defaultViewType,
            settings.landscapeListColumns,
        ) { viewType, landscape -> viewType to landscape },
        combine(
            settings.watchedFolder,
            settings.watchedFolderDeleteAfterImport,
        ) { folder, delete -> folder to delete },
        combine(settings.storagePathTemplate, settings.storagePathEnabled) { template, enabled ->
            (template ?: DEFAULT_STORAGE_PATH_TEMPLATE) to enabled
        },
        combine(
            settings.aiEnabled,
            settings.aiBaseUrl,
            settings.aiApiKey,
            settings.aiModel,
            settings.aiMaxTagsPerDoc,
        ) { enabled, baseUrl, apiKey, model, maxTags ->
            AiSettings(enabled, baseUrl, apiKey, model, maxTags)
        },
    ) { (location, lang, retention), (viewType, landscape), (watched, watchedDelete), (template, pathEnabled), ai ->
        val preview = StoragePathTemplate.expand(template, PREVIEW_CONTEXT)
        SettingsUiState(
            libraryLocation = location,
            ocrLanguage = lang,
            trashRetentionDays = retention,
            defaultViewType = viewType,
            landscapeListColumns = landscape,
            watchedFolder = watched,
            watchedFolderDeleteAfterImport = watchedDelete,
            storagePathTemplate = template,
            storagePathEnabled = pathEnabled,
            storagePathPreview = preview.getOrNull(),
            storagePathError = preview.exceptionOrNull()?.message,
            aiEnabled = ai.enabled,
            aiBaseUrl = ai.baseUrl,
            aiApiKey = ai.apiKey,
            aiModel = ai.model,
            aiMaxTagsPerDoc = ai.maxTagsPerDoc,
        )
    }.stateIn(viewModelScope, WhileSubscribedTimeout, SettingsUiState())

    val reviewMode: StateFlow<ReviewMode> =
        session.reviewMode.stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    fun setOcrLanguage(lang: String) = viewModelScope.launch { settings.setOcrLanguage(lang) }

    fun setTrashRetentionDays(days: Int) =
        viewModelScope.launch { settings.setTrashRetentionDays(days) }

    fun setDefaultViewType(v: ViewType) =
        viewModelScope.launch { settings.setDefaultViewType(v) }

    fun setLandscapeListColumns(enabled: Boolean) =
        viewModelScope.launch { settings.setLandscapeListColumns(enabled) }

    fun setWatchedFolder(location: String?) =
        viewModelScope.launch { settings.setWatchedFolder(location) }

    fun setWatchedFolderDeleteAfterImport(enabled: Boolean) =
        viewModelScope.launch { settings.setWatchedFolderDeleteAfterImport(enabled) }

    fun setStoragePathTemplate(template: String) =
        viewModelScope.launch { settings.setStoragePathTemplate(template) }

    fun setStoragePathEnabled(enabled: Boolean) = viewModelScope.launch {
        // Re-derive validity from the stored template rather than the projected UI state, which
        // can be the stale default when nothing is currently collecting `state`.
        val valid = if (!enabled) {
            true
        } else {
            val template = settings.storagePathTemplate.first() ?: DEFAULT_STORAGE_PATH_TEMPLATE
            StoragePathTemplate.expand(template, PREVIEW_CONTEXT).isSuccess
        }
        if (valid) settings.setStoragePathEnabled(enabled)
    }

    fun setAiEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAiEnabled(enabled) }

    fun setAiBaseUrl(url: String) = viewModelScope.launch { settings.setAiBaseUrl(url) }

    fun setAiApiKey(key: String) = viewModelScope.launch { settings.setAiApiKey(key) }

    fun setAiModel(model: String) = viewModelScope.launch { settings.setAiModel(model) }

    fun setAiMaxTagsPerDoc(value: Int) =
        viewModelScope.launch { settings.setAiMaxTagsPerDoc(value) }

    /**
     * Drop all cached thumbnails so they regenerate at the current render quality. Invokes [onDone]
     * with the number cleared (for a confirmation snackbar).
     */
    fun clearThumbnailCache(onDone: (Int) -> Unit) = viewModelScope.launch {
        onDone(session.content.clearThumbnailCache())
    }

    /** Switch to UNTAGGED_ONLY. No migration prompt (the stored reviewed flag is ignored). */
    fun setUntaggedOnlyMode() =
        viewModelScope.launch { session.setReviewMode(ReviewMode.UNTAGGED_ONLY) }

    /**
     * Switch to INBOX_WORKFLOW, running the chosen one-shot migration of the reviewed marker.
     * Order: run the migration writes first, then set the mode, so the flag state is ready when
     * the mode flips.
     */
    fun setInboxWorkflowMode(migration: ReviewMigration) = viewModelScope.launch {
        when (migration) {
            ReviewMigration.MARK_ALL -> session.markReviewedForMigration(onlyTagged = false)
            ReviewMigration.MARK_TAGGED -> session.markReviewedForMigration(onlyTagged = true)
            ReviewMigration.MARK_NONE -> Unit
        }
        session.setReviewMode(ReviewMode.INBOX_WORKFLOW)
    }

    private companion object {
        const val DEFAULT_STORAGE_PATH_TEMPLATE = "{doc_date:yyyy}/{title}.pdf"
        val PREVIEW_CONTEXT = StoragePathContext(
            title = "Electricity bill",
            docDate = "2026-07-20",
            docType = "Invoice",
            correspondent = "Example Energy",
            tags = listOf("home", "utilities"),
            originalFileName = "scan.pdf",
        )
    }
}
