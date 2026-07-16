package dev.npn.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.app.GridSizing
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val libraryLocation: String? = null,
    val ocrLanguage: String = "eng",
    val trashRetentionDays: Int = 30,
    val defaultViewType: ViewType = ViewType.GRID,
    val gridColumnWidth: Int = GridSizing.DEFAULT_COLUMN_WIDTH,
    val landscapeListColumns: Boolean = false,
)

/** Choice offered by the 1->2 (UNTAGGED_ONLY -> INBOX_WORKFLOW) migration prompt. */
enum class ReviewMigration { MARK_ALL, MARK_TAGGED, MARK_NONE }

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val session: LibrarySession,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settings.libraryLocation,
        settings.ocrLanguage,
        settings.trashRetentionDays,
        settings.defaultViewType,
        combine(settings.gridColumnWidth, settings.landscapeListColumns) { w, l -> w to l },
    ) { location, lang, retention, viewType, grid ->
        SettingsUiState(
            libraryLocation = location,
            ocrLanguage = lang,
            trashRetentionDays = retention,
            defaultViewType = viewType,
            gridColumnWidth = grid.first,
            landscapeListColumns = grid.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val reviewMode: StateFlow<ReviewMode> =
        session.reviewMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewMode.DEFAULT)

    fun setOcrLanguage(lang: String) = viewModelScope.launch { settings.setOcrLanguage(lang) }

    fun setTrashRetentionDays(days: Int) =
        viewModelScope.launch { settings.setTrashRetentionDays(days) }

    fun setDefaultViewType(v: ViewType) =
        viewModelScope.launch { settings.setDefaultViewType(v) }

    fun setGridColumnWidth(dp: Int) =
        viewModelScope.launch { settings.setGridColumnWidth(dp) }

    fun setLandscapeListColumns(enabled: Boolean) =
        viewModelScope.launch { settings.setLandscapeListColumns(enabled) }

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
