package dev.npn.app.ui.settings

import androidx.compose.runtime.Composable
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.core.app.ViewType
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_default_view
import no_papers_needed.composeapp.generated.resources.common_settings_grid_size_help
import no_papers_needed.composeapp.generated.resources.common_settings_landscape_columns
import no_papers_needed.composeapp.generated.resources.common_settings_landscape_columns_help
import no_papers_needed.composeapp.generated.resources.common_settings_language
import no_papers_needed.composeapp.generated.resources.common_settings_language_dutch
import no_papers_needed.composeapp.generated.resources.common_settings_language_english
import no_papers_needed.composeapp.generated.resources.common_settings_language_french
import no_papers_needed.composeapp.generated.resources.common_settings_language_german
import no_papers_needed.composeapp.generated.resources.common_settings_language_help
import no_papers_needed.composeapp.generated.resources.common_settings_language_portuguese
import no_papers_needed.composeapp.generated.resources.common_settings_language_spanish
import no_papers_needed.composeapp.generated.resources.common_settings_language_system
import no_papers_needed.composeapp.generated.resources.common_settings_views
import no_papers_needed.composeapp.generated.resources.common_view_grid
import no_papers_needed.composeapp.generated.resources.common_view_list
import org.jetbrains.compose.resources.stringResource

/** Languages the app UI can be switched to. Null = follow the system locale. */
private val APP_LANGUAGES: List<String?> = listOf(null, "en", "nl", "de", "fr", "es", "pt")

/** Device-local presentation: how the library renders by default, and in which language. */
@Composable
internal fun AppearanceSettingsPane(context: SettingsPaneContext) {
    SettingsSection(title = stringResource(Res.string.common_settings_views)) {
        SettingsDropdown(
            buttonLabel =
                stringResource(
                    Res.string.common_settings_default_view,
                    viewTypeLabel(context.state.defaultViewType),
                ),
            options = ViewType.entries,
            optionLabel = { viewTypeLabel(it) },
            onSelect = { v -> context.vm.edit { it.copy(defaultViewType = v) } },
        )
        HelpText(stringResource(Res.string.common_settings_grid_size_help))
        SwitchRow(
            label = stringResource(Res.string.common_settings_landscape_columns),
            help = stringResource(Res.string.common_settings_landscape_columns_help),
            checked = context.state.landscapeListColumns,
            onCheckedChange = { on -> context.vm.edit { it.copy(landscapeListColumns = on) } },
        )
    }

    SettingsSection(title = stringResource(Res.string.common_settings_language)) {
        SettingsDropdown(
            buttonLabel = appLanguageLabel(context.state.appLanguage),
            options = APP_LANGUAGES,
            optionLabel = { appLanguageLabel(it) },
            onSelect = { lang -> context.vm.edit { it.copy(appLanguage = lang) } },
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

@Composable
private fun appLanguageLabel(language: String?): String =
    when (language) {
        null -> stringResource(Res.string.common_settings_language_system)
        "en" -> stringResource(Res.string.common_settings_language_english)
        "nl" -> stringResource(Res.string.common_settings_language_dutch)
        "de" -> stringResource(Res.string.common_settings_language_german)
        "fr" -> stringResource(Res.string.common_settings_language_french)
        "es" -> stringResource(Res.string.common_settings_language_spanish)
        "pt" -> stringResource(Res.string.common_settings_language_portuguese)
        else -> language
    }
