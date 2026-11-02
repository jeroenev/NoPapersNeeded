package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.npn.app.platform.isAppPrivateLocation
import dev.npn.app.platform.supportsSafStorage
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.common.TypedTextField
import dev.npn.app.ui.common.rememberStorageAccess
import dev.npn.app.ui.common.resolve
import dev.npn.core.model.FieldType
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathExpansion
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.settings.LibraryLocations
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
    // A direct library on shared storage, which Android's all-files grant governs; a cloud
    // library's working copy is in the app's own storage, which no grant governs.
    val needsGrant = location != null && !safLibrary && !isAppPrivateLocation(location)
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
        if (needsGrant && !storage.usable) {
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
    // Rendered against a fictional document in the app's language.
    val sample =
        StoragePathContext.sample(
            fieldTypes = fieldTypes,
            title = stringResource(Res.string.common_settings_storage_sample_title),
            docType = stringResource(Res.string.common_settings_storage_sample_type),
            correspondent = stringResource(Res.string.common_settings_storage_sample_correspondent),
            tags =
                listOf(
                    stringResource(Res.string.common_settings_storage_sample_tag_home),
                    stringResource(Res.string.common_settings_storage_sample_tag_utilities),
                ),
        )
    val expansion = StoragePathTemplate.expand(template, sample)
    val error = (expansion as? StoragePathExpansion.Invalid)?.error
    SwitchRow(
        label = stringResource(Res.string.common_settings_storage_organize_paths),
        help = stringResource(Res.string.common_settings_storage_organize_help),
        checked = enabled,
        enabled = enabled || error == null,
        onCheckedChange = onEnabledChange,
    )
    TypedTextField(
        value = template,
        onValueChange = onTemplateChange,
        onDone = {},
        label = stringResource(Res.string.common_settings_storage_path_template),
        supportingText = {
            Text(
                text =
                    when (expansion) {
                        is StoragePathExpansion.Path -> {
                            stringResource(Res.string.common_settings_storage_preview, expansion.path)
                        }

                        is StoragePathExpansion.Invalid -> {
                            expansion.error.text().resolve()
                        }
                    },
            )
        },
        isError = error != null,
        modifier = Modifier.fillMaxWidth(),
    )
    HelpText(stringResource(Res.string.common_settings_storage_variables, STORAGE_PATH_VARIABLES))
}
