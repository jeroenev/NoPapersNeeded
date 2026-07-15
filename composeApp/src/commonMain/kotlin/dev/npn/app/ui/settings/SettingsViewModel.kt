package dev.npn.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.ViewType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val libraryLocation: String? = null,
    val ocrLanguage: String = "eng",
    val trashRetentionDays: Int = 30,
    val defaultViewType: ViewType = ViewType.LIST,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settings.libraryLocation,
        settings.ocrLanguage,
        settings.trashRetentionDays,
        settings.defaultViewType,
    ) { location, lang, retention, viewType ->
        SettingsUiState(
            libraryLocation = location,
            ocrLanguage = lang,
            trashRetentionDays = retention,
            defaultViewType = viewType,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setOcrLanguage(lang: String) = viewModelScope.launch { settings.setOcrLanguage(lang) }

    fun setTrashRetentionDays(days: Int) =
        viewModelScope.launch { settings.setTrashRetentionDays(days) }

    fun setDefaultViewType(v: ViewType) =
        viewModelScope.launch { settings.setDefaultViewType(v) }
}
