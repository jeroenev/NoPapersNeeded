@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.NumericField
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.persistDirectoryAccess
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.StandardField
import dev.npn.core.pathrules.StoragePathContext
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_settings_about
import no_papers_needed.composeapp.generated.resources.common_settings_about_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_assistant
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching_help
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching_rules
import no_papers_needed.composeapp.generated.resources.common_settings_change_library
import no_papers_needed.composeapp.generated.resources.common_settings_choose_folder
import no_papers_needed.composeapp.generated.resources.common_settings_clear_thumbnail_cache
import no_papers_needed.composeapp.generated.resources.common_settings_default_view
import no_papers_needed.composeapp.generated.resources.common_settings_document_fields
import no_papers_needed.composeapp.generated.resources.common_settings_document_fields_help
import no_papers_needed.composeapp.generated.resources.common_settings_enable_inbox
import no_papers_needed.composeapp.generated.resources.common_settings_grid_size_help
import no_papers_needed.composeapp.generated.resources.common_settings_inbox_help
import no_papers_needed.composeapp.generated.resources.common_settings_inbox_migration_question
import no_papers_needed.composeapp.generated.resources.common_settings_landscape_columns
import no_papers_needed.composeapp.generated.resources.common_settings_landscape_columns_help
import no_papers_needed.composeapp.generated.resources.common_settings_language
import no_papers_needed.composeapp.generated.resources.common_settings_language_dutch
import no_papers_needed.composeapp.generated.resources.common_settings_language_english
import no_papers_needed.composeapp.generated.resources.common_settings_language_help
import no_papers_needed.composeapp.generated.resources.common_settings_language_system
import no_papers_needed.composeapp.generated.resources.common_settings_library
import no_papers_needed.composeapp.generated.resources.common_settings_mark_all_reviewed
import no_papers_needed.composeapp.generated.resources.common_settings_mark_none_reviewed
import no_papers_needed.composeapp.generated.resources.common_settings_mark_tagged_reviewed
import no_papers_needed.composeapp.generated.resources.common_settings_no_library
import no_papers_needed.composeapp.generated.resources.common_settings_no_watched_folder
import no_papers_needed.composeapp.generated.resources.common_settings_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_language_code
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_language_help
import no_papers_needed.composeapp.generated.resources.common_settings_product_version
import no_papers_needed.composeapp.generated.resources.common_settings_remove_after_import
import no_papers_needed.composeapp.generated.resources.common_settings_remove_after_import_help
import no_papers_needed.composeapp.generated.resources.common_settings_review_workflow
import no_papers_needed.composeapp.generated.resources.common_settings_stop_watching
import no_papers_needed.composeapp.generated.resources.common_settings_storage
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_absolute
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_backslash
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_blank
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_date_format_only
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_invalid
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_invalid_segment
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_reserved
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_traversal
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_unclosed
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_unexpected_closing
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_unknown_variable
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_whitespace
import no_papers_needed.composeapp.generated.resources.common_settings_storage_organize_help
import no_papers_needed.composeapp.generated.resources.common_settings_storage_organize_paths
import no_papers_needed.composeapp.generated.resources.common_settings_storage_path_template
import no_papers_needed.composeapp.generated.resources.common_settings_storage_preview
import no_papers_needed.composeapp.generated.resources.common_settings_storage_sample_correspondent
import no_papers_needed.composeapp.generated.resources.common_settings_storage_sample_tag_home
import no_papers_needed.composeapp.generated.resources.common_settings_storage_sample_tag_utilities
import no_papers_needed.composeapp.generated.resources.common_settings_storage_sample_title
import no_papers_needed.composeapp.generated.resources.common_settings_storage_sample_type
import no_papers_needed.composeapp.generated.resources.common_settings_storage_variables
import no_papers_needed.composeapp.generated.resources.common_settings_syncs_across_devices
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnail_cache_empty
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnail_cache_help
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnails_cleared
import no_papers_needed.composeapp.generated.resources.common_settings_title
import no_papers_needed.composeapp.generated.resources.common_settings_trash
import no_papers_needed.composeapp.generated.resources.common_settings_trash_retention_days
import no_papers_needed.composeapp.generated.resources.common_settings_views
import no_papers_needed.composeapp.generated.resources.common_settings_watched_folder
import no_papers_needed.composeapp.generated.resources.common_settings_watched_folder_help
import no_papers_needed.composeapp.generated.resources.common_view_grid
import no_papers_needed.composeapp.generated.resources.common_view_list
import no_papers_needed.composeapp.generated.resources.detail_correspondent
import no_papers_needed.composeapp.generated.resources.detail_document_date_plain
import no_papers_needed.composeapp.generated.resources.detail_document_type
import no_papers_needed.composeapp.generated.resources.detail_note
import no_papers_needed.composeapp.generated.resources.queue_settings_help
import no_papers_needed.composeapp.generated.resources.queue_settings_section
import no_papers_needed.composeapp.generated.resources.queue_show
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

const val NPN_VERSION = "0.1.0"

/** Accepted range for the trash-retention field (0 = purge immediately; capped at four digits). */
private val TRASH_RETENTION_RANGE = 0..9999
private const val TRASH_RETENTION_MAX_DIGITS = 4

@Composable
fun SettingsScreen(
    env: AppEnvironment,
    session: LibrarySession,
    onBack: () -> Unit,
    onChangeLibrary: () -> Unit,
    onAutoMatchRules: () -> Unit,
    onProcessingQueue: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(env.settings, session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val aiModels by vm.aiModels.collectAsStateWithLifecycle()
    val reviewMode by vm.reviewMode.collectAsStateWithLifecycle()
    val standardFields by vm.standardFields.collectAsStateWithLifecycle()
    val storagePath =
        evaluateStoragePath(
            state.storagePathTemplate,
            StoragePathContext(
                title = stringResource(Res.string.common_settings_storage_sample_title),
                docDate = "2026-07-20",
                docType = stringResource(Res.string.common_settings_storage_sample_type),
                correspondent = stringResource(Res.string.common_settings_storage_sample_correspondent),
                tags =
                    listOf(
                        stringResource(Res.string.common_settings_storage_sample_tag_home),
                        stringResource(Res.string.common_settings_storage_sample_tag_utilities),
                    ),
                originalFileName = "scan.pdf",
            ),
        )

    val snackbar = remember { SnackbarHostState() }
    // Surface failed settings writes (properties-file I/O, SAF errors) on the same snackbar host.
    SnackbarMessages(vm.messages, snackbar)
    var thumbnailResult by remember { mutableStateOf<Int?>(null) }
    val thumbnailMessage =
        thumbnailResult?.let { count ->
            if (count > 0) {
                pluralStringResource(Res.plurals.common_settings_thumbnails_cleared, count, count)
            } else {
                stringResource(Res.string.common_settings_thumbnail_cache_empty)
            }
        }
    LaunchedEffect(thumbnailMessage) {
        thumbnailMessage?.let {
            snackbar.showSnackbar(it)
            thumbnailResult = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.common_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionHeader(stringResource(Res.string.common_settings_library))
            Text(
                text = state.libraryLocation ?: stringResource(Res.string.common_settings_no_library),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onChangeLibrary) {
                Text(stringResource(Res.string.common_settings_change_library))
            }

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_watched_folder))
            WatchedFolderSection(
                watchedFolder = state.watchedFolder,
                deleteAfterImport = state.watchedFolderDeleteAfterImport,
                onSetFolder = { folder -> vm.edit { it.copy(watchedFolder = folder) } },
                onSetDeleteAfterImport = { on -> vm.edit { it.copy(watchedFolderDeleteAfterImport = on) } },
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_ocr))
            OutlinedTextField(
                value = state.ocrLanguage,
                onValueChange = { lang -> vm.edit { it.copy(ocrLanguage = lang) } },
                label = { Text(stringResource(Res.string.common_settings_ocr_language_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(Res.string.common_settings_ocr_language_help, "eng, nld, fra"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_ai_assistant))
            AiAssistantSection(
                state = state,
                models = aiModels,
                vm = vm,
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_trash))
            NumericField(
                value = state.trashRetentionDays,
                onValueChange = { days -> vm.edit { it.copy(trashRetentionDays = days) } },
                range = TRASH_RETENTION_RANGE,
                label = stringResource(Res.string.common_settings_trash_retention_days),
                maxDigits = TRASH_RETENTION_MAX_DIGITS,
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_views))
            DefaultViewTypeDropdown(
                current = state.defaultViewType,
                onSelect = { v -> vm.edit { it.copy(defaultViewType = v) } },
            )
            Text(
                stringResource(Res.string.common_settings_grid_size_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LandscapeListToggle(
                enabled = state.landscapeListColumns,
                onToggle = { on -> vm.edit { it.copy(landscapeListColumns = on) } },
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_language))
            AppLanguageDropdown(
                current = state.appLanguage,
                onSelect = { lang -> vm.edit { it.copy(appLanguage = lang) } },
            )
            Text(
                stringResource(Res.string.common_settings_language_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_storage))
            StoragePathRuleEditor(
                template = state.storagePathTemplate,
                enabled = state.storagePathEnabled,
                preview = storagePath.preview,
                error = storagePath.error,
                onTemplateChange = { template -> vm.edit { it.copy(storagePathTemplate = template) } },
                onEnabledChange = vm::setStoragePathEnabled,
            )
            OutlinedButton(onClick = {
                vm.clearThumbnailCache { count -> thumbnailResult = count }
            }) { Text(stringResource(Res.string.common_settings_clear_thumbnail_cache)) }
            Text(
                stringResource(Res.string.common_settings_thumbnail_cache_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_document_fields))
            StandardFieldsSection(
                enabled = standardFields,
                onSetEnabled = vm::setStandardFieldEnabled,
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_review_workflow))
            InboxWorkflowToggle(
                current = reviewMode,
                onUntaggedOnly = vm::setUntaggedOnlyMode,
                onInboxWorkflow = vm::setInboxWorkflowMode,
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_auto_matching))
            OutlinedButton(onClick = onAutoMatchRules) {
                Text(stringResource(Res.string.common_settings_auto_matching_rules))
            }
            Text(
                stringResource(Res.string.common_settings_auto_matching_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.queue_settings_section))
            OutlinedButton(onClick = onProcessingQueue) {
                Text(stringResource(Res.string.queue_show))
            }
            Text(
                stringResource(Res.string.queue_settings_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionHeader(stringResource(Res.string.common_settings_about))
            Text(
                stringResource(
                    Res.string.common_settings_product_version,
                    stringResource(Res.string.common_app_name),
                    NPN_VERSION,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(Res.string.common_settings_about_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StoragePathRuleEditor(
    template: String,
    enabled: Boolean,
    preview: String?,
    error: StoragePathError?,
    onTemplateChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.common_settings_storage_organize_paths),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(Res.string.common_settings_storage_organize_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                enabled = error == null,
                onCheckedChange = onEnabledChange,
            )
        }
        OutlinedTextField(
            value = template,
            onValueChange = onTemplateChange,
            label = { Text(stringResource(Res.string.common_settings_storage_path_template)) },
            supportingText = {
                Text(
                    error?.localizedMessage()
                        ?: stringResource(Res.string.common_settings_storage_preview, preview.orEmpty()),
                )
            },
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                Res.string.common_settings_storage_variables,
                "{title}, {doc_date}, {doc_date:yyyy-MM}, {docType}, {correspondent}, {tags}",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WatchedFolderSection(
    watchedFolder: String?,
    deleteAfterImport: Boolean,
    onSetFolder: (String?) -> Unit,
    onSetDeleteAfterImport: (Boolean) -> Unit,
) {
    val picker =
        rememberDirectoryPickerLauncher { dir ->
            if (dir != null) onSetFolder(persistDirectoryAccess(dir))
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.common_settings_watched_folder_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = watchedFolder ?: stringResource(Res.string.common_settings_no_watched_folder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch() }) {
                Text(stringResource(Res.string.common_settings_choose_folder))
            }
            if (watchedFolder != null) {
                OutlinedButton(onClick = { onSetFolder(null) }) {
                    Text(stringResource(Res.string.common_settings_stop_watching))
                }
            }
        }
        if (watchedFolder != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.common_settings_remove_after_import),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(Res.string.common_settings_remove_after_import_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = deleteAfterImport, onCheckedChange = onSetDeleteAfterImport)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DefaultViewTypeDropdown(
    current: ViewType,
    onSelect: (ViewType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        Res.string.common_settings_default_view,
                        viewTypeLabelResource(current),
                    ),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ViewType.entries.forEach { vt ->
                DropdownMenuItem(
                    text = { Text(viewTypeLabelResource(vt)) },
                    onClick = {
                        onSelect(vt)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Languages the app UI can be switched to. Null = follow the system locale. */
private val APP_LANGUAGES: List<String?> = listOf(null, "en", "nl")

@Composable
private fun appLanguageLabel(language: String?): String =
    when (language) {
        null -> stringResource(Res.string.common_settings_language_system)
        "en" -> stringResource(Res.string.common_settings_language_english)
        "nl" -> stringResource(Res.string.common_settings_language_dutch)
        else -> language
    }

@Composable
private fun AppLanguageDropdown(
    current: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(appLanguageLabel(current))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            APP_LANGUAGES.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(appLanguageLabel(lang)) },
                    onClick = {
                        onSelect(lang)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Library-wide visibility of the built-in document fields (synced across devices). Hiding a
 * field only hides its editor on the document panel; stored values survive and come back when
 * the field is enabled again. Title is always shown, so it is not listed here.
 */
@Composable
private fun StandardFieldsSection(
    enabled: Set<StandardField>,
    onSetEnabled: (StandardField, Boolean) -> Unit,
) {
    Text(
        stringResource(Res.string.common_settings_document_fields_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    StandardField.entries.forEach { field ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                standardFieldLabel(field),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = field in enabled,
                onCheckedChange = { onSetEnabled(field, it) },
            )
        }
    }
}

@Composable
internal fun standardFieldLabel(field: StandardField): String =
    when (field) {
        StandardField.CORRESPONDENT -> stringResource(Res.string.detail_correspondent)
        StandardField.DOC_TYPE -> stringResource(Res.string.detail_document_type)
        StandardField.DOC_DATE -> stringResource(Res.string.detail_document_date_plain)
        StandardField.NOTES -> stringResource(Res.string.detail_note)
    }

@Composable
private fun LandscapeListToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.common_settings_landscape_columns),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(Res.string.common_settings_landscape_columns_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun InboxWorkflowToggle(
    current: ReviewMode,
    onUntaggedOnly: () -> Unit,
    onInboxWorkflow: (ReviewMigration) -> Unit,
) {
    var showMigration by remember { mutableStateOf(false) }
    val enabled = current == ReviewMode.INBOX_WORKFLOW

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.common_settings_enable_inbox),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(Res.string.common_settings_inbox_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    if (checked) showMigration = true else onUntaggedOnly()
                },
            )
        }
        Text(
            stringResource(Res.string.common_settings_syncs_across_devices),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showMigration) {
        AlertDialog(
            onDismissRequest = { showMigration = false },
            title = { Text(stringResource(Res.string.common_settings_enable_inbox)) },
            text = {
                Text(
                    stringResource(Res.string.common_settings_inbox_migration_question),
                )
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        onInboxWorkflow(ReviewMigration.MARK_ALL)
                        showMigration = false
                    }) { Text(stringResource(Res.string.common_settings_mark_all_reviewed)) }
                    TextButton(onClick = {
                        onInboxWorkflow(ReviewMigration.MARK_TAGGED)
                        showMigration = false
                    }) { Text(stringResource(Res.string.common_settings_mark_tagged_reviewed)) }
                    TextButton(onClick = {
                        onInboxWorkflow(ReviewMigration.MARK_NONE)
                        showMigration = false
                    }) { Text(stringResource(Res.string.common_settings_mark_none_reviewed)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMigration = false }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun viewTypeLabelResource(viewType: ViewType): String =
    when (viewType) {
        ViewType.LIST -> stringResource(Res.string.common_view_list)
        ViewType.GRID -> stringResource(Res.string.common_view_grid)
    }

@Composable
private fun StoragePathError.localizedMessage(): String =
    when (kind) {
        StoragePathErrorKind.UNCLOSED_PLACEHOLDER -> {
            stringResource(
                Res.string.common_settings_storage_error_unclosed,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.UNEXPECTED_CLOSING_BRACE -> {
            stringResource(
                Res.string.common_settings_storage_error_unexpected_closing,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.DATE_FORMAT_ONLY -> {
            stringResource(
                Res.string.common_settings_storage_error_date_format_only,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.UNKNOWN_VARIABLE -> {
            stringResource(
                Res.string.common_settings_storage_error_unknown_variable,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.BLANK -> {
            stringResource(Res.string.common_settings_storage_error_blank)
        }

        StoragePathErrorKind.ABSOLUTE -> {
            stringResource(Res.string.common_settings_storage_error_absolute)
        }

        StoragePathErrorKind.BACKSLASH -> {
            stringResource(Res.string.common_settings_storage_error_backslash)
        }

        StoragePathErrorKind.TRAVERSAL -> {
            stringResource(Res.string.common_settings_storage_error_traversal)
        }

        StoragePathErrorKind.RESERVED_DIRECTORY -> {
            stringResource(
                Res.string.common_settings_storage_error_reserved,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.WHITESPACE -> {
            stringResource(Res.string.common_settings_storage_error_whitespace)
        }

        StoragePathErrorKind.INVALID_SEGMENT -> {
            stringResource(
                Res.string.common_settings_storage_error_invalid_segment,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.INVALID -> {
            stringResource(Res.string.common_settings_storage_error_invalid)
        }
    }
