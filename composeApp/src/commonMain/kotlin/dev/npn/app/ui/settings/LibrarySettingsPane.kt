package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.npn.app.platform.supportsSafStorage
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.common.rememberStorageAccess
import dev.npn.app.ui.common.resolve
import dev.npn.core.app.LibraryLocations
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.pathrules.StoragePathContext
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_advanced
import no_papers_needed.composeapp.generated.resources.common_settings_change_library
import no_papers_needed.composeapp.generated.resources.common_settings_clear_thumbnail_cache
import no_papers_needed.composeapp.generated.resources.common_settings_library_saf_note
import no_papers_needed.composeapp.generated.resources.common_settings_no_library
import no_papers_needed.composeapp.generated.resources.common_settings_storage
import no_papers_needed.composeapp.generated.resources.common_settings_storage_access_missing
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
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnail_cache_help
import no_papers_needed.composeapp.generated.resources.common_settings_use_saf
import no_papers_needed.composeapp.generated.resources.common_settings_use_saf_help
import no_papers_needed.composeapp.generated.resources.common_storage_access_grant
import org.jetbrains.compose.resources.stringResource

/** Variables accepted by the storage-path template, echoed in the field's help copy. */
private const val STORAGE_PATH_VARIABLES =
    "{title}, {doc_date}, {doc_date:yyyy-MM}, {docType}, {correspondent}, {tags}, {field:key}, {field:key:yyyy}"

/**
 * Where documents live: which folder is the library, how PDFs are laid out inside it, and the
 * derived thumbnail cache that can be thrown away at any time.
 */
@Composable
internal fun LibrarySettingsPane(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    val location = state.settings.snapshot.libraryLocation
    val safLibrary = location != null && LibraryLocations.isContentUri(location)
    val storage = rememberStorageAccess()
    SettingsSection(title = null) {
        Text(
            text = location ?: stringResource(Res.string.common_settings_no_library),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (safLibrary) {
            HelpText(stringResource(Res.string.common_settings_library_saf_note))
        }
        // A direct library whose grant was revoked still opens what the app itself wrote, but not
        // what Syncthing wrote next to it: say so where the user looks for the library.
        if (location != null && !safLibrary && !storage.usable) {
            HelpText(stringResource(Res.string.common_settings_storage_access_missing))
            SettingsActionRow(
                label = stringResource(Res.string.common_storage_access_grant),
                onClick = storage.request,
            )
        }
        SettingsActionRow(
            label = stringResource(Res.string.common_settings_change_library),
            onClick = actions::changeLibrary,
        )
    }

    SettingsSection(title = stringResource(Res.string.common_settings_storage)) {
        StoragePathRuleEditor(
            template = state.settings.storagePathTemplate,
            enabled = state.settings.storagePathEnabled,
            fieldTypes = state.fields.associate { it.key to it.type },
            onTemplateChange = actions::setStoragePathTemplate,
            onEnabledChange = actions::setStoragePathEnabled,
        )
        SettingsActionRow(
            label = stringResource(Res.string.common_settings_clear_thumbnail_cache),
            onClick = actions::clearThumbnailCache,
            help = stringResource(Res.string.common_settings_thumbnail_cache_help),
        )
    }

    // Android only: the Storage Access Framework stays available for folders that have no path
    // (cloud document providers). It changes what the next folder choice persists, nothing else.
    if (supportsSafStorage()) {
        SettingsSection(title = stringResource(Res.string.common_settings_advanced)) {
            SwitchRow(
                label = stringResource(Res.string.common_settings_use_saf),
                help = stringResource(Res.string.common_settings_use_saf_help),
                checked = state.settings.snapshot.useSafStorage,
                onCheckedChange = { on -> actions.edit { it.copy(useSafStorage = on) } },
            )
        }
    }
}

/**
 * Toggle plus template for automatic path organisation. Switching the rule on is gated on the
 * template parsing cleanly, so an invalid template can never be armed; switching it off never is,
 * so a rule whose template was edited into an invalid one can still be stopped. The preview renders
 * the template against a sample document so the effect is visible before it applies to real files.
 */
@Composable
internal fun StoragePathRuleEditor(
    template: String,
    enabled: Boolean,
    /** The library's field types: a date pattern is valid on any field the registry calls a date. */
    fieldTypes: Map<String, FieldType>,
    onTemplateChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val evaluation = evaluateStoragePath(template, sampleStoragePathContext(fieldTypes))
    SwitchRow(
        label = stringResource(Res.string.common_settings_storage_organize_paths),
        help = stringResource(Res.string.common_settings_storage_organize_help),
        checked = enabled,
        enabled = enabled || evaluation.error == null,
        onCheckedChange = onEnabledChange,
    )
    OutlinedTextField(
        value = template,
        onValueChange = onTemplateChange,
        label = { Text(text = stringResource(Res.string.common_settings_storage_path_template)) },
        supportingText = {
            Text(
                text =
                    evaluation.error?.text()?.resolve()
                        ?: stringResource(Res.string.common_settings_storage_preview, evaluation.preview.orEmpty()),
            )
        },
        isError = evaluation.error != null,
        modifier = Modifier.fillMaxWidth(),
    )
    HelpText(stringResource(Res.string.common_settings_storage_variables, STORAGE_PATH_VARIABLES))
}

/** The fictional document the template preview is rendered against. */
@Composable
private fun sampleStoragePathContext(fieldTypes: Map<String, FieldType>): StoragePathContext =
    StoragePathContext(
        fields =
            mapOf(
                FieldKeys.TITLE to stringResource(Res.string.common_settings_storage_sample_title),
                FieldKeys.DOC_DATE to "2026-07-20",
                FieldKeys.DOC_TYPE to stringResource(Res.string.common_settings_storage_sample_type),
                FieldKeys.CORRESPONDENT to stringResource(Res.string.common_settings_storage_sample_correspondent),
            ),
        tags =
            listOf(
                stringResource(Res.string.common_settings_storage_sample_tag_home),
                stringResource(Res.string.common_settings_storage_sample_tag_utilities),
            ),
        originalFileName = "scan.pdf",
        fieldTypes = fieldTypes,
    )
