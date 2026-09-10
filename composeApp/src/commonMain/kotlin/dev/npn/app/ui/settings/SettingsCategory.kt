package dev.npn.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_about
import no_papers_needed.composeapp.generated.resources.common_settings_about_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_assistant
import no_papers_needed.composeapp.generated.resources.common_settings_ai_auto_on_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ai_provider_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_test
import no_papers_needed.composeapp.generated.resources.common_settings_api_key
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching_rules
import no_papers_needed.composeapp.generated.resources.common_settings_category_appearance
import no_papers_needed.composeapp.generated.resources.common_settings_category_documents
import no_papers_needed.composeapp.generated.resources.common_settings_category_import
import no_papers_needed.composeapp.generated.resources.common_settings_change_library
import no_papers_needed.composeapp.generated.resources.common_settings_choose_folder
import no_papers_needed.composeapp.generated.resources.common_settings_clear_thumbnail_cache
import no_papers_needed.composeapp.generated.resources.common_settings_document_fields
import no_papers_needed.composeapp.generated.resources.common_settings_enable_ai
import no_papers_needed.composeapp.generated.resources.common_settings_enable_inbox
import no_papers_needed.composeapp.generated.resources.common_settings_grid_size_help
import no_papers_needed.composeapp.generated.resources.common_settings_landscape_columns
import no_papers_needed.composeapp.generated.resources.common_settings_language
import no_papers_needed.composeapp.generated.resources.common_settings_library
import no_papers_needed.composeapp.generated.resources.common_settings_load_models
import no_papers_needed.composeapp.generated.resources.common_settings_max_new_tags
import no_papers_needed.composeapp.generated.resources.common_settings_max_suggested_tags
import no_papers_needed.composeapp.generated.resources.common_settings_model
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_language_code
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_help
import no_papers_needed.composeapp.generated.resources.common_settings_remove_after_import
import no_papers_needed.composeapp.generated.resources.common_settings_server_url
import no_papers_needed.composeapp.generated.resources.common_settings_storage_organize_paths
import no_papers_needed.composeapp.generated.resources.common_settings_storage_path_template
import no_papers_needed.composeapp.generated.resources.common_settings_trash
import no_papers_needed.composeapp.generated.resources.common_settings_trash_retention_days
import no_papers_needed.composeapp.generated.resources.common_settings_views
import no_papers_needed.composeapp.generated.resources.common_settings_watched_folder
import no_papers_needed.composeapp.generated.resources.detail_add_field
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Stable identity of a settings category. The lowercased name is what travels in
 * [dev.npn.app.ui.nav.Route.Settings], so renaming an entry breaks saved back stacks and deep
 * links; add a new one instead.
 */
enum class SettingsCategoryId {
    LIBRARY,
    IMPORT,
    DOCUMENTS,
    AI,
    APPEARANCE,
    ABOUT,
    ;

    val routeKey: String get() = name.lowercase()

    companion object {
        /** Resolves a route argument, tolerating stale/unknown keys from old back stacks. */
        fun fromRouteKey(key: String?): SettingsCategoryId? = entries.firstOrNull { it.routeKey == key }
    }
}

/**
 * Everything a settings pane may need, bundled so the registry can hand every category the same
 * argument. Panes read what they use and ignore the rest; adding a dependency touches this class
 * and one pane instead of threading a parameter through the whole screen.
 */
@Stable
internal class SettingsPaneContext(
    val vm: SettingsViewModel,
    val state: SettingsUiState,
    val aiModels: AiModelListState,
    val aiSelfTest: AiSelfTestState,
    val reviewMode: ReviewMode,
    val fields: List<FieldDef>,
    val fieldUsage: Map<String, Int>,
    val onChangeLibrary: () -> Unit,
    val onAutoMatchRules: () -> Unit,
    val onThumbnailsCleared: (Int) -> Unit,
)

/**
 * One entry in the settings table of contents.
 *
 * [keywords] are the labels of the individual settings the pane contains. They exist so search can
 * answer "where do I change the thumbnail cache?" without every setting needing its own registry
 * entry. They deliberately reuse the controls' resource keys rather than adding translated copies.
 */
@Immutable
internal class SettingsCategory(
    val id: SettingsCategoryId,
    val title: StringResource,
    val icon: ImageVector,
    val keywords: List<StringResource>,
    val content: @Composable (SettingsPaneContext) -> Unit,
)

/**
 * The settings table of contents, in display order. This list is the only place that knows which
 * categories exist: the navigation list, the wide-layout detail pane, route resolution, and search
 * are all derived from it, so a new category is one entry plus one pane file.
 */
internal val SettingsCategories: List<SettingsCategory> =
    listOf(
        SettingsCategory(
            id = SettingsCategoryId.LIBRARY,
            title = Res.string.common_settings_library,
            icon = Icons.Outlined.FolderOpen,
            keywords =
                listOf(
                    Res.string.common_settings_change_library,
                    Res.string.common_settings_storage_organize_paths,
                    Res.string.common_settings_storage_path_template,
                    Res.string.common_settings_clear_thumbnail_cache,
                ),
            content = { LibrarySettingsPane(it) },
        ),
        SettingsCategory(
            id = SettingsCategoryId.IMPORT,
            title = Res.string.common_settings_category_import,
            icon = Icons.Outlined.MoveToInbox,
            keywords =
                listOf(
                    Res.string.common_settings_watched_folder,
                    Res.string.common_settings_choose_folder,
                    Res.string.common_settings_remove_after_import,
                    Res.string.common_settings_ocr_language_code,
                    Res.string.common_settings_auto_matching_rules,
                ),
            content = { ImportSettingsPane(it) },
        ),
        SettingsCategory(
            id = SettingsCategoryId.DOCUMENTS,
            title = Res.string.common_settings_category_documents,
            icon = Icons.Outlined.Description,
            keywords =
                listOf(
                    Res.string.common_settings_document_fields,
                    Res.string.detail_add_field,
                    Res.string.common_settings_enable_inbox,
                    Res.string.common_settings_trash,
                    Res.string.common_settings_trash_retention_days,
                ),
            content = { DocumentSettingsPane(it) },
        ),
        SettingsCategory(
            id = SettingsCategoryId.AI,
            title = Res.string.common_settings_ai_assistant,
            icon = Icons.Outlined.AutoAwesome,
            keywords =
                listOf(
                    Res.string.common_settings_enable_ai,
                    Res.string.common_settings_ai_provider_help,
                    Res.string.common_settings_server_url,
                    Res.string.common_settings_api_key,
                    Res.string.common_settings_model,
                    Res.string.common_settings_load_models,
                    Res.string.common_settings_reasoning_help,
                    Res.string.common_settings_max_suggested_tags,
                    Res.string.common_settings_max_new_tags,
                    Res.string.common_settings_ai_auto_on_ocr,
                    Res.string.common_settings_ai_test,
                ),
            content = { AiSettingsPane(it) },
        ),
        SettingsCategory(
            id = SettingsCategoryId.APPEARANCE,
            title = Res.string.common_settings_category_appearance,
            icon = Icons.Outlined.Palette,
            keywords =
                listOf(
                    Res.string.common_settings_views,
                    Res.string.common_settings_grid_size_help,
                    Res.string.common_settings_landscape_columns,
                    Res.string.common_settings_language,
                ),
            content = { AppearanceSettingsPane(it) },
        ),
        SettingsCategory(
            id = SettingsCategoryId.ABOUT,
            title = Res.string.common_settings_about,
            icon = Icons.Outlined.Info,
            keywords = listOf(Res.string.common_settings_about_help),
            content = { AboutSettingsPane() },
        ),
    )

internal fun settingsCategory(id: SettingsCategoryId): SettingsCategory = SettingsCategories.first { it.id == id }

/** A category with its resource labels resolved, so matching can happen outside composition. */
@Immutable
internal data class SettingsCategoryLabels(
    val category: SettingsCategory,
    val title: String,
    val keywords: List<String>,
) {
    fun matches(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        return title.contains(trimmed, ignoreCase = true) || matchedKeyword(trimmed) != null
    }

    /** The setting label that made this category match, shown as the search result's subtitle. */
    fun matchedKeyword(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        return keywords.firstOrNull { it.contains(trimmed, ignoreCase = true) }
    }
}

@Composable
internal fun settingsCategoryLabels(): List<SettingsCategoryLabels> =
    SettingsCategories.map { category ->
        SettingsCategoryLabels(
            category = category,
            title = stringResource(category.title),
            keywords = category.keywords.map { stringResource(it) },
        )
    }
