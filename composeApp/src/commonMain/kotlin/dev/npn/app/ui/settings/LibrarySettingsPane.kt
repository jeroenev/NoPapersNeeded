package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.npn.core.pathrules.StoragePathContext
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_change_library
import no_papers_needed.composeapp.generated.resources.common_settings_clear_thumbnail_cache
import no_papers_needed.composeapp.generated.resources.common_settings_no_library
import no_papers_needed.composeapp.generated.resources.common_settings_storage
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
import org.jetbrains.compose.resources.stringResource

/** Variables accepted by the storage-path template, echoed in the field's help copy. */
private const val STORAGE_PATH_VARIABLES = "{title}, {doc_date}, {doc_date:yyyy-MM}, {docType}, {correspondent}, {tags}"

/**
 * Where documents live: which folder is the library, how PDFs are laid out inside it, and the
 * derived thumbnail cache that can be thrown away at any time.
 */
@Composable
internal fun LibrarySettingsPane(context: SettingsPaneContext) {
    SettingsGroup(title = null) {
        Text(
            text = context.state.libraryLocation ?: stringResource(Res.string.common_settings_no_library),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsActionRow(
            label = stringResource(Res.string.common_settings_change_library),
            onClick = context.onChangeLibrary,
        )
    }

    SettingsGroup(title = stringResource(Res.string.common_settings_storage)) {
        StoragePathRuleEditor(
            template = context.state.storagePathTemplate,
            enabled = context.state.storagePathEnabled,
            onTemplateChange = { template -> context.vm.edit { it.copy(storagePathTemplate = template) } },
            onEnabledChange = context.vm::setStoragePathEnabled,
        )
        SettingsActionRow(
            label = stringResource(Res.string.common_settings_clear_thumbnail_cache),
            onClick = { context.vm.clearThumbnailCache(context.onThumbnailsCleared) },
            help = stringResource(Res.string.common_settings_thumbnail_cache_help),
        )
    }
}

/**
 * Toggle plus template for automatic path organisation. The toggle is gated on the template
 * parsing cleanly, so an invalid template can never be armed; the preview renders the template
 * against a sample document so the effect is visible before it applies to real files.
 */
@Composable
private fun StoragePathRuleEditor(
    template: String,
    enabled: Boolean,
    onTemplateChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val evaluation = evaluateStoragePath(template, sampleStoragePathContext())
    SettingsSwitchRow(
        title = stringResource(Res.string.common_settings_storage_organize_paths),
        help = stringResource(Res.string.common_settings_storage_organize_help),
        checked = enabled,
        enabled = evaluation.error == null,
        onCheckedChange = onEnabledChange,
    )
    OutlinedTextField(
        value = template,
        onValueChange = onTemplateChange,
        label = { Text(text = stringResource(Res.string.common_settings_storage_path_template)) },
        supportingText = {
            Text(
                text =
                    evaluation.error?.localizedMessage()
                        ?: stringResource(Res.string.common_settings_storage_preview, evaluation.preview.orEmpty()),
            )
        },
        isError = evaluation.error != null,
        modifier = Modifier.fillMaxWidth(),
    )
    SettingsHelpText(stringResource(Res.string.common_settings_storage_variables, STORAGE_PATH_VARIABLES))
}

/** The fictional document the template preview is rendered against. */
@Composable
private fun sampleStoragePathContext(): StoragePathContext =
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
    )
