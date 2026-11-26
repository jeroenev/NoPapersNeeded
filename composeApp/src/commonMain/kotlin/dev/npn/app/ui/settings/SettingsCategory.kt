package dev.npn.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.npn.app.platform.appLockRecoveryFile
import dev.npn.app.platform.ocrHasLanguageSetting
import dev.npn.app.platform.supportsEmailImport
import dev.npn.app.platform.supportsSafStorage
import dev.npn.app.ui.automation.AutomationSettingsPane
import dev.npn.app.ui.diagnostics.ProblemReportState
import dev.npn.core.mail.MailState
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.rules.AutoMatchRule
import kotlinx.serialization.Serializable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.accessibility_reduce_motion
import no_papers_needed.composeapp.generated.resources.accessibility_title
import no_papers_needed.composeapp.generated.resources.automation_ai_open
import no_papers_needed.composeapp.generated.resources.automation_ai_title
import no_papers_needed.composeapp.generated.resources.automation_inbox_open
import no_papers_needed.composeapp.generated.resources.automation_inbox_title
import no_papers_needed.composeapp.generated.resources.automation_manual_title
import no_papers_needed.composeapp.generated.resources.automation_rules_title
import no_papers_needed.composeapp.generated.resources.automation_title
import no_papers_needed.composeapp.generated.resources.common_settings_about
import no_papers_needed.composeapp.generated.resources.common_settings_about_help
import no_papers_needed.composeapp.generated.resources.common_settings_advanced
import no_papers_needed.composeapp.generated.resources.common_settings_ai_assistant
import no_papers_needed.composeapp.generated.resources.common_settings_ai_auto_on_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ai_ocr_auto
import no_papers_needed.composeapp.generated.resources.common_settings_ai_provider
import no_papers_needed.composeapp.generated.resources.common_settings_ai_provider_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_reset
import no_papers_needed.composeapp.generated.resources.common_settings_ai_test
import no_papers_needed.composeapp.generated.resources.common_settings_api_key
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching_rules
import no_papers_needed.composeapp.generated.resources.common_settings_category_appearance
import no_papers_needed.composeapp.generated.resources.common_settings_category_cloud
import no_papers_needed.composeapp.generated.resources.common_settings_category_documents
import no_papers_needed.composeapp.generated.resources.common_settings_category_import
import no_papers_needed.composeapp.generated.resources.common_settings_change_library
import no_papers_needed.composeapp.generated.resources.common_settings_choose_folder
import no_papers_needed.composeapp.generated.resources.common_settings_clear_thumbnail_cache
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_auto
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_change_folder
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_connect
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_disconnect
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_check
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_interval
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_remote
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_schedule
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_title
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_status
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sync_now
import no_papers_needed.composeapp.generated.resources.common_settings_default_view
import no_papers_needed.composeapp.generated.resources.common_settings_document_fields
import no_papers_needed.composeapp.generated.resources.common_settings_enable_ai
import no_papers_needed.composeapp.generated.resources.common_settings_enable_inbox
import no_papers_needed.composeapp.generated.resources.common_settings_grid_size_help
import no_papers_needed.composeapp.generated.resources.common_settings_landscape_columns
import no_papers_needed.composeapp.generated.resources.common_settings_language
import no_papers_needed.composeapp.generated.resources.common_settings_language_system
import no_papers_needed.composeapp.generated.resources.common_settings_library
import no_papers_needed.composeapp.generated.resources.common_settings_load_models
import no_papers_needed.composeapp.generated.resources.common_settings_max_new_tags
import no_papers_needed.composeapp.generated.resources.common_settings_max_run_requests
import no_papers_needed.composeapp.generated.resources.common_settings_max_suggested_tags
import no_papers_needed.composeapp.generated.resources.common_settings_model
import no_papers_needed.composeapp.generated.resources.common_settings_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_match_app_language
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_other_language
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_assistant
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_edit
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_help
import no_papers_needed.composeapp.generated.resources.common_settings_reminders
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_enabled
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_fields
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_lead_days
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_notify_here
import no_papers_needed.composeapp.generated.resources.common_settings_remove_after_import
import no_papers_needed.composeapp.generated.resources.common_settings_report_problem
import no_papers_needed.composeapp.generated.resources.common_settings_report_problem_help
import no_papers_needed.composeapp.generated.resources.common_settings_review_workflow
import no_papers_needed.composeapp.generated.resources.common_settings_server_url
import no_papers_needed.composeapp.generated.resources.common_settings_stop_watching
import no_papers_needed.composeapp.generated.resources.common_settings_storage
import no_papers_needed.composeapp.generated.resources.common_settings_storage_organize_paths
import no_papers_needed.composeapp.generated.resources.common_settings_storage_path_template
import no_papers_needed.composeapp.generated.resources.common_settings_support_help
import no_papers_needed.composeapp.generated.resources.common_settings_support_sponsor
import no_papers_needed.composeapp.generated.resources.common_settings_support_title
import no_papers_needed.composeapp.generated.resources.common_settings_trash
import no_papers_needed.composeapp.generated.resources.common_settings_trash_retention_days
import no_papers_needed.composeapp.generated.resources.common_settings_update_check
import no_papers_needed.composeapp.generated.resources.common_settings_use_saf
import no_papers_needed.composeapp.generated.resources.common_settings_verify_library
import no_papers_needed.composeapp.generated.resources.common_settings_views
import no_papers_needed.composeapp.generated.resources.common_settings_watched_folder
import no_papers_needed.composeapp.generated.resources.common_settings_whats_new
import no_papers_needed.composeapp.generated.resources.common_sync_health_retry
import no_papers_needed.composeapp.generated.resources.common_sync_health_title
import no_papers_needed.composeapp.generated.resources.detail_add_field
import no_papers_needed.composeapp.generated.resources.email_account
import no_papers_needed.composeapp.generated.resources.email_add_filter
import no_papers_needed.composeapp.generated.resources.email_allow_plain
import no_papers_needed.composeapp.generated.resources.email_check_now
import no_papers_needed.composeapp.generated.resources.email_clear_password
import no_papers_needed.composeapp.generated.resources.email_enabled
import no_papers_needed.composeapp.generated.resources.email_filter_attachment
import no_papers_needed.composeapp.generated.resources.email_filter_from
import no_papers_needed.composeapp.generated.resources.email_filter_subject
import no_papers_needed.composeapp.generated.resources.email_filters
import no_papers_needed.composeapp.generated.resources.email_folder
import no_papers_needed.composeapp.generated.resources.email_host
import no_papers_needed.composeapp.generated.resources.email_interval
import no_papers_needed.composeapp.generated.resources.email_last_check
import no_papers_needed.composeapp.generated.resources.email_last_error
import no_papers_needed.composeapp.generated.resources.email_move_folder
import no_papers_needed.composeapp.generated.resources.email_never_checked
import no_papers_needed.composeapp.generated.resources.email_password
import no_papers_needed.composeapp.generated.resources.email_port
import no_papers_needed.composeapp.generated.resources.email_post_action
import no_papers_needed.composeapp.generated.resources.email_post_keep
import no_papers_needed.composeapp.generated.resources.email_post_mark_read
import no_papers_needed.composeapp.generated.resources.email_post_move
import no_papers_needed.composeapp.generated.resources.email_remove_filter
import no_papers_needed.composeapp.generated.resources.email_save
import no_papers_needed.composeapp.generated.resources.email_save_pdf
import no_papers_needed.composeapp.generated.resources.email_security
import no_papers_needed.composeapp.generated.resources.email_security_plain
import no_papers_needed.composeapp.generated.resources.email_security_ssl
import no_papers_needed.composeapp.generated.resources.email_security_starttls
import no_papers_needed.composeapp.generated.resources.email_start_everything
import no_papers_needed.composeapp.generated.resources.email_start_from
import no_papers_needed.composeapp.generated.resources.email_start_last_30_days
import no_papers_needed.composeapp.generated.resources.email_start_last_year
import no_papers_needed.composeapp.generated.resources.email_start_new_only
import no_papers_needed.composeapp.generated.resources.email_test
import no_papers_needed.composeapp.generated.resources.email_title
import no_papers_needed.composeapp.generated.resources.email_username
import no_papers_needed.composeapp.generated.resources.onboarding_add_device
import no_papers_needed.composeapp.generated.resources.security_app_lock
import no_papers_needed.composeapp.generated.resources.security_app_lock_help
import no_papers_needed.composeapp.generated.resources.security_change_pin
import no_papers_needed.composeapp.generated.resources.security_lock_after
import no_papers_needed.composeapp.generated.resources.security_lock_fifteen_minutes
import no_papers_needed.composeapp.generated.resources.security_lock_five_minutes
import no_papers_needed.composeapp.generated.resources.security_lock_immediately
import no_papers_needed.composeapp.generated.resources.security_lock_now
import no_papers_needed.composeapp.generated.resources.security_lock_one_minute
import no_papers_needed.composeapp.generated.resources.security_not_encrypted
import no_papers_needed.composeapp.generated.resources.security_recovery_android
import no_papers_needed.composeapp.generated.resources.security_recovery_desktop
import no_papers_needed.composeapp.generated.resources.security_recovery_title
import no_papers_needed.composeapp.generated.resources.security_set_pin
import no_papers_needed.composeapp.generated.resources.security_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Stable identity of a settings category. The lowercased name is what travels in
 * [dev.npn.app.ui.nav.Route.Settings], so renaming an entry breaks saved back stacks and deep
 * links; add a new one instead.
 */
@Serializable
enum class SettingsCategoryId {
    LIBRARY,
    IMPORT,
    EMAIL,
    AUTOMATION,
    DOCUMENTS,
    CLOUD_SYNC,
    AI,
    APPEARANCE,
    SECURITY,
    ABOUT,
    ;

    val routeKey: String get() = name.lowercase()

    companion object {
        /** Resolves a route argument, tolerating stale/unknown keys from old back stacks. */
        fun fromRouteKey(key: String?): SettingsCategoryId? = entries.firstOrNull { it.routeKey == key }
    }
}

/**
 * Everything a settings pane may show, bundled so the registry can hand every category the same
 * argument. Panes read what they use and ignore the rest; adding a dependency touches this class
 * and one pane instead of threading a parameter through the whole screen.
 */
internal data class SettingsPaneState(
    val settings: SettingsUiState,
    val aiModels: AiModelListState,
    val aiSelfTest: AiSelfTestState,
    val reviewMode: ReviewMode,
    val fields: List<FieldDef>,
    val fieldUsage: Map<String, Int>,
    /** The field manager's open dialog, if any. */
    val fieldDialog: FieldDialog? = null,
    val cloud: CloudSyncState = CloudSyncState(),
    /** The library's auto-match rules in run order; null until they load. */
    val rules: List<AutoMatchRule>? = null,
    /** The library check's live state, and its last result. */
    val verification: LibraryVerificationState = LibraryVerificationState(),
    /** The IMAP account, last check and credential state email import shows. */
    val mail: MailState = MailState(),
    /** The app-lock dialog Settings has open, if any. */
    val securityDialog: SecurityDialog? = null,
    /** The check of the current secret [securityDialog] asks for. */
    val securityCheck: SecurityCheck = SecurityCheck(),
    /** A "Report a problem" bundle the About pane writes and shares. */
    val problemReport: ProblemReportState = ProblemReportState.Idle,
)

/** What a settings pane can do: change a setting ([SettingsActions]), or open what one leads to. */
internal interface SettingsPaneActions :
    SettingsActions,
    ReminderSettingsActions {
    /** Pick another library folder; a document with unsaved changes is offered back first. */
    fun changeLibrary()

    fun openAutoMatchRules()

    /** Open another category's pane, as picking it in the list does. */
    fun openCategory(id: SettingsCategoryId)

    /** Open the field manager's [dialog], or change what it holds; null closes it. */
    fun openFieldDialog(dialog: FieldDialog?)

    /** Open [docId]'s detail, for a conflict or check finding that still names a document. */
    fun openDocument(docId: String)

    /** Open the app-lock dialog, or close the open one with null. */
    fun openSecurityDialog(dialog: SecurityDialog?)
}

/**
 * One entry in the settings table of contents.
 *
 * [keywords] are the labels of the individual settings the pane contains. They exist so search can
 * answer "where do I change the thumbnail cache?" without every setting needing its own registry
 * entry. They deliberately reuse the controls' resource keys rather than adding translated copies;
 * a label with a value in it ("Default view: %1$s") is searched by its name. Every label a pane
 * shows must be among them, which `SettingsSearchCoverageTest` checks by rendering the panes.
 *
 * [advancedKeywords] are those of [keywords] the pane keeps behind its Advanced disclosure: a search
 * that matches one opens the pane with the disclosure expanded ([rememberAdvancedExpanded]).
 */
@Immutable
internal class SettingsCategory(
    val id: SettingsCategoryId,
    val title: StringResource,
    val icon: ImageVector,
    val keywords: List<StringResource>,
    val content: @Composable (SettingsPaneState, SettingsPaneActions) -> Unit,
    val advancedKeywords: List<StringResource> = emptyList(),
)

/**
 * The settings table of contents, in display order. This list is the only place that knows which
 * categories exist: the navigation list, the wide-layout detail pane, route resolution, and search
 * are all derived from it, so a new category is one entry plus one pane file.
 */
internal val SettingsCategories: List<SettingsCategory> =
    listOfNotNull(
        SettingsCategory(
            id = SettingsCategoryId.LIBRARY,
            title = Res.string.common_settings_library,
            icon = Icons.Outlined.FolderOpen,
            keywords =
                listOfNotNull(
                    Res.string.common_settings_change_library,
                    Res.string.onboarding_add_device,
                    Res.string.common_settings_storage,
                    Res.string.common_settings_storage_organize_paths,
                    Res.string.common_settings_storage_path_template,
                    Res.string.common_settings_advanced,
                    Res.string.common_settings_clear_thumbnail_cache,
                    Res.string.common_settings_verify_library,
                    // Only Android offers the Storage Access Framework.
                    Res.string.common_settings_use_saf.takeIf { supportsSafStorage() },
                ),
            content = { state, actions -> LibrarySettingsPane(state, actions) },
            advancedKeywords =
                listOfNotNull(
                    Res.string.common_settings_advanced,
                    Res.string.common_settings_clear_thumbnail_cache,
                    Res.string.common_settings_use_saf.takeIf { supportsSafStorage() },
                ),
        ),
        SettingsCategory(
            id = SettingsCategoryId.IMPORT,
            title = Res.string.common_settings_category_import,
            icon = Icons.Outlined.MoveToInbox,
            keywords =
                listOfNotNull(
                    Res.string.common_settings_watched_folder,
                    Res.string.common_settings_choose_folder,
                    Res.string.common_settings_stop_watching,
                    Res.string.common_settings_remove_after_import,
                    Res.string.common_settings_ocr,
                    // Only desktop shows the OCR language settings; Android's OCR takes no language.
                    Res.string.common_settings_ocr_match_app_language.takeIf { ocrHasLanguageSetting() },
                    Res.string.common_settings_ocr_other_language.takeIf { ocrHasLanguageSetting() },
                ),
            content = { state, actions -> ImportSettingsPane(state, actions) },
        ),
        // Email import is a desktop feature; Android has neither the IMAP client nor the keyring,
        // so the category is absent there rather than a pane of dead controls.
        if (supportsEmailImport()) {
            SettingsCategory(
                id = SettingsCategoryId.EMAIL,
                title = Res.string.email_title,
                icon = Icons.Outlined.Email,
                keywords =
                    listOf(
                        Res.string.email_account,
                        Res.string.email_enabled,
                        Res.string.email_host,
                        Res.string.email_port,
                        Res.string.email_security,
                        Res.string.email_security_ssl,
                        Res.string.email_security_starttls,
                        Res.string.email_security_plain,
                        Res.string.email_username,
                        Res.string.email_password,
                        Res.string.email_folder,
                        Res.string.email_allow_plain,
                        Res.string.email_interval,
                        Res.string.email_start_from,
                        Res.string.email_start_new_only,
                        Res.string.email_start_last_30_days,
                        Res.string.email_start_last_year,
                        Res.string.email_start_everything,
                        Res.string.email_filters,
                        Res.string.email_filter_from,
                        Res.string.email_filter_subject,
                        Res.string.email_filter_attachment,
                        Res.string.email_add_filter,
                        Res.string.email_remove_filter,
                        Res.string.email_post_action,
                        Res.string.email_post_keep,
                        Res.string.email_post_mark_read,
                        Res.string.email_post_move,
                        Res.string.email_move_folder,
                        Res.string.email_save_pdf,
                        Res.string.email_save,
                        Res.string.email_test,
                        Res.string.email_check_now,
                        Res.string.email_clear_password,
                        Res.string.email_last_check,
                        Res.string.email_never_checked,
                        Res.string.email_last_error,
                    ),
                content = { state, actions -> EmailSettingsPane(state, actions) },
            )
        } else {
            null
        },
        SettingsCategory(
            id = SettingsCategoryId.AUTOMATION,
            title = Res.string.automation_title,
            icon = Icons.Outlined.AutoMode,
            keywords =
                listOf(
                    Res.string.automation_rules_title,
                    Res.string.common_settings_auto_matching_rules,
                    Res.string.automation_ai_title,
                    Res.string.automation_ai_open,
                    Res.string.automation_inbox_title,
                    Res.string.automation_inbox_open,
                    Res.string.automation_manual_title,
                ),
            content = { state, actions -> AutomationSettingsPane(state, actions) },
        ),
        SettingsCategory(
            id = SettingsCategoryId.DOCUMENTS,
            title = Res.string.common_settings_category_documents,
            icon = Icons.Outlined.Description,
            keywords =
                listOf(
                    Res.string.common_settings_document_fields,
                    Res.string.detail_add_field,
                    Res.string.common_settings_review_workflow,
                    Res.string.common_settings_enable_inbox,
                    Res.string.common_settings_reminders,
                    Res.string.common_settings_reminders_enabled,
                    Res.string.common_settings_reminders_fields,
                    Res.string.common_settings_reminders_lead_days,
                    Res.string.common_settings_reminders_notify_here,
                    Res.string.common_settings_trash,
                    Res.string.common_settings_trash_retention_days,
                ),
            content = { state, actions -> DocumentSettingsPane(state, actions) },
        ),
        SettingsCategory(
            id = SettingsCategoryId.CLOUD_SYNC,
            title = Res.string.common_settings_category_cloud,
            icon = Icons.Outlined.CloudSync,
            keywords =
                listOf(
                    Res.string.common_settings_cloud_engine_check,
                    Res.string.onboarding_add_device,
                    Res.string.common_settings_cloud_setup_title,
                    Res.string.common_settings_cloud_connect,
                    Res.string.common_settings_cloud_remote,
                    Res.string.common_settings_cloud_change_folder,
                    Res.string.common_settings_cloud_disconnect,
                    Res.string.common_settings_cloud_schedule,
                    Res.string.common_settings_cloud_auto,
                    Res.string.common_settings_cloud_interval,
                    Res.string.common_settings_cloud_status,
                    Res.string.common_settings_cloud_sync_now,
                    Res.string.common_sync_health_title,
                    Res.string.common_sync_health_retry,
                ),
            content = { state, actions -> CloudSyncSettingsPane(state, actions) },
        ),
        SettingsCategory(
            id = SettingsCategoryId.AI,
            title = Res.string.common_settings_ai_assistant,
            icon = Icons.Outlined.AutoAwesome,
            keywords =
                listOf(
                    Res.string.common_settings_enable_ai,
                    Res.string.common_settings_ai_provider,
                    Res.string.common_settings_ai_provider_help,
                    Res.string.common_settings_server_url,
                    Res.string.common_settings_api_key,
                    Res.string.common_settings_model,
                    Res.string.common_settings_load_models,
                    Res.string.common_settings_reasoning_assistant,
                    Res.string.common_settings_reasoning_edit,
                    Res.string.common_settings_reasoning_help,
                    Res.string.common_settings_max_suggested_tags,
                    Res.string.common_settings_max_new_tags,
                    Res.string.common_settings_max_run_requests,
                    Res.string.common_settings_ai_auto_on_ocr,
                    Res.string.common_settings_ai_ocr_auto,
                    Res.string.common_settings_ai_test,
                    Res.string.common_settings_ai_spend,
                    Res.string.common_settings_ai_spend_reset,
                    // The reasoning and tag-limit controls sit behind this disclosure.
                    Res.string.common_settings_advanced,
                ),
            content = { state, actions -> AiSettingsPane(state, actions) },
            advancedKeywords =
                listOf(
                    Res.string.common_settings_advanced,
                    Res.string.common_settings_reasoning_assistant,
                    Res.string.common_settings_reasoning_edit,
                    Res.string.common_settings_reasoning_help,
                    Res.string.common_settings_max_suggested_tags,
                    Res.string.common_settings_max_new_tags,
                    Res.string.common_settings_max_run_requests,
                ),
        ),
        SettingsCategory(
            id = SettingsCategoryId.APPEARANCE,
            title = Res.string.common_settings_category_appearance,
            icon = Icons.Outlined.Palette,
            keywords =
                listOf(
                    Res.string.common_settings_views,
                    Res.string.common_settings_default_view,
                    Res.string.common_settings_grid_size_help,
                    Res.string.common_settings_landscape_columns,
                    Res.string.accessibility_title,
                    Res.string.accessibility_reduce_motion,
                    Res.string.common_settings_language,
                    Res.string.common_settings_language_system,
                ),
            content = { state, actions -> AppearanceSettingsPane(state, actions) },
        ),
        SettingsCategory(
            id = SettingsCategoryId.SECURITY,
            title = Res.string.security_title,
            icon = Icons.Outlined.Lock,
            keywords =
                listOf(
                    Res.string.security_app_lock,
                    Res.string.security_app_lock_help,
                    Res.string.security_set_pin,
                    Res.string.security_change_pin,
                    Res.string.security_lock_now,
                    Res.string.security_not_encrypted,
                    Res.string.security_lock_after,
                    Res.string.security_lock_immediately,
                    Res.string.security_lock_one_minute,
                    Res.string.security_lock_five_minutes,
                    Res.string.security_lock_fifteen_minutes,
                    Res.string.security_recovery_title,
                    if (appLockRecoveryFile() != null) {
                        Res.string.security_recovery_desktop
                    } else {
                        Res.string.security_recovery_android
                    },
                ),
            content = { state, actions -> SecuritySettingsPane(state, actions) },
        ),
        SettingsCategory(
            id = SettingsCategoryId.ABOUT,
            title = Res.string.common_settings_about,
            icon = Icons.Outlined.Info,
            keywords =
                listOf(
                    Res.string.common_settings_about_help,
                    Res.string.common_settings_update_check,
                    Res.string.common_settings_support_title,
                    Res.string.common_settings_support_help,
                    Res.string.common_settings_support_sponsor,
                    Res.string.common_settings_whats_new,
                    Res.string.common_settings_report_problem,
                    Res.string.common_settings_report_problem_help,
                ),
            content = { state, actions -> AboutSettingsPane(state, actions) },
        ),
    )

internal fun settingsCategory(id: SettingsCategoryId): SettingsCategory = SettingsCategories.first { it.id == id }

/** A category with its resource labels resolved, so matching can happen outside composition. */
@Immutable
internal data class SettingsCategoryLabels(
    val category: SettingsCategory,
    val title: String,
    val keywords: List<String>,
    /** The resolved [SettingsCategory.advancedKeywords]. */
    val advancedKeywords: List<String> = emptyList(),
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

    /** Whether [query] finds a setting behind this pane's Advanced disclosure, which then opens expanded. */
    fun revealsAdvanced(query: String): Boolean {
        val trimmed = query.trim()
        return trimmed.isNotEmpty() && advancedKeywords.any { it.contains(trimmed, ignoreCase = true) }
    }
}

@Composable
internal fun settingsCategoryLabels(): List<SettingsCategoryLabels> =
    SettingsCategories.map { category ->
        SettingsCategoryLabels(
            category = category,
            title = stringResource(category.title),
            keywords = category.keywords.map { searchableLabel(stringResource(it)) },
            advancedKeywords = category.advancedKeywords.map { searchableLabel(stringResource(it)) },
        )
    }

/** A label's name as search matches it: without the value a label like "Default view: %1$s" holds. */
internal fun searchableLabel(label: String): String =
    label
        .replace(PLACEHOLDER, "")
        .trim()
        .trimEnd(':')
        .trim()

private val PLACEHOLDER = Regex("""%\d+\$[sd]""")
