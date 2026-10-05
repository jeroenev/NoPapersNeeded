package dev.npn.app.ui.settings

import androidx.compose.runtime.Composable
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.common.UiLanguage
import dev.npn.app.ui.common.appLanguageName
import dev.npn.core.app.ViewType
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_default_view
import no_papers_needed.composeapp.generated.resources.common_settings_grid_size_help
import no_papers_needed.composeapp.generated.resources.common_settings_landscape_columns
import no_papers_needed.composeapp.generated.resources.common_settings_landscape_columns_help
import no_papers_needed.composeapp.generated.resources.common_settings_language
import no_papers_needed.composeapp.generated.resources.common_settings_language_help
import no_papers_needed.composeapp.generated.resources.common_settings_views
import no_papers_needed.composeapp.generated.resources.common_view_grid
import no_papers_needed.composeapp.generated.resources.common_view_list
import org.jetbrains.compose.resources.stringResource

/** Languages the app UI can be switched to. Null = follow the system locale. */
private val APP_LANGUAGES: List<String?> = listOf(null) + UiLanguage.entries.map { it.tag }

/** Device-local presentation: how the library renders by default, and in which language. */
@Composable
internal fun AppearanceSettingsPane(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    SettingsSection(title = stringResource(Res.string.common_settings_views)) {
        Dropdown(
            selected =
                stringResource(
                    Res.string.common_settings_default_view,
                    viewTypeLabel(state.settings.snapshot.defaultViewType),
                ),
            options = ViewType.entries,
            optionLabel = { viewTypeLabel(it) },
            onSelect = { v -> actions.edit { it.copy(defaultViewType = v) } },
        )
        HelpText(stringResource(Res.string.common_settings_grid_size_help))
        SwitchRow(
            label = stringResource(Res.string.common_settings_landscape_columns),
            help = stringResource(Res.string.common_settings_landscape_columns_help),
            checked = state.settings.snapshot.landscapeListColumns,
            onCheckedChange = { on -> actions.edit { it.copy(landscapeListColumns = on) } },
        )
    }

    SettingsSection(title = stringResource(Res.string.common_settings_language)) {
        Dropdown(
            selected = appLanguageName(state.settings.snapshot.appLanguage),
            options = APP_LANGUAGES,
            optionLabel = { appLanguageName(it) },
            onSelect = { lang -> actions.edit { it.copy(appLanguage = lang) } },
        )
        HelpText(stringResource(Res.string.common_settings_language_help))
    }
}

@Composable
private fun viewTypeLabel(viewType: ViewType): String =
    when (viewType) {
        ViewType.LIST -> stringResource(Res.string.common_view_list)
        ViewType.GRID -> stringResource(Res.string.common_view_grid)
    }
