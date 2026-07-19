package dev.npn.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode
import dev.npn.app.ui.common.WhileSubscribedTimeout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
)

/** Choice offered by the 1->2 (UNTAGGED_ONLY -> INBOX_WORKFLOW) migration prompt. */
enum class ReviewMigration { MARK_ALL, MARK_TAGGED, MARK_NONE }

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
    ) { (location, lang, retention), (viewType, landscape), (watched, watchedDelete) ->
        SettingsUiState(
            libraryLocation = location,
            ocrLanguage = lang,
            trashRetentionDays = retention,
            defaultViewType = viewType,
            landscapeListColumns = landscape,
            watchedFolder = watched,
            watchedFolderDeleteAfterImport = watchedDelete,
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
}
