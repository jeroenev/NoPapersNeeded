package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.FolderAccess
import dev.npn.app.ui.common.persistDirectoryAccess
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching_help
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching_rules
import no_papers_needed.composeapp.generated.resources.common_settings_choose_folder
import no_papers_needed.composeapp.generated.resources.common_settings_no_watched_folder
import no_papers_needed.composeapp.generated.resources.common_settings_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_language_code
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_language_help
import no_papers_needed.composeapp.generated.resources.common_settings_remove_after_import
import no_papers_needed.composeapp.generated.resources.common_settings_remove_after_import_help
import no_papers_needed.composeapp.generated.resources.common_settings_stop_watching
import no_papers_needed.composeapp.generated.resources.common_settings_watched_folder
import no_papers_needed.composeapp.generated.resources.common_settings_watched_folder_help
import org.jetbrains.compose.resources.stringResource

/** Example codes shown with the OCR language field. */
private const val OCR_LANGUAGE_EXAMPLES = "eng, nld, fra"

/**
 * How documents get into the library and what happens to them on the way in: the watched folder,
 * text recognition, and the rules that fill in metadata from the recognised text.
 */
@Composable
internal fun ImportSettingsPane(context: SettingsPaneContext) {
    SettingsGroup(title = stringResource(Res.string.common_settings_watched_folder)) {
        WatchedFolderSettings(
            watchedFolder = context.state.watchedFolder,
            useSaf = context.state.useSafStorage,
            deleteAfterImport = context.state.watchedFolderDeleteAfterImport,
            onSetFolder = { folder -> context.vm.edit { it.copy(watchedFolder = folder) } },
            onSetDeleteAfterImport = { on -> context.vm.edit { it.copy(watchedFolderDeleteAfterImport = on) } },
        )
    }

    SettingsGroup(title = stringResource(Res.string.common_settings_ocr)) {
        OutlinedTextField(
            value = context.state.ocrLanguage,
            onValueChange = { lang -> context.vm.edit { it.copy(ocrLanguage = lang) } },
            label = { Text(text = stringResource(Res.string.common_settings_ocr_language_code)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsHelpText(stringResource(Res.string.common_settings_ocr_language_help, OCR_LANGUAGE_EXAMPLES))
    }

    SettingsGroup(title = stringResource(Res.string.common_settings_auto_matching)) {
        SettingsActionRow(
            label = stringResource(Res.string.common_settings_auto_matching_rules),
            onClick = context.onAutoMatchRules,
            help = stringResource(Res.string.common_settings_auto_matching_help),
        )
    }
}

@Composable
private fun WatchedFolderSettings(
    watchedFolder: String?,
    useSaf: Boolean,
    deleteAfterImport: Boolean,
    onSetFolder: (String?) -> Unit,
    onSetDeleteAfterImport: (Boolean) -> Unit,
) {
    // The inbox is imported from, never written as a library, so a folder that is not on device
    // storage may still be watched through SAF: prefer the configured mode, fall back to the other.
    val picker =
        rememberDirectoryPickerLauncher { dir ->
            if (dir == null) return@rememberDirectoryPickerLauncher
            val preferred = if (useSaf) FolderAccess.SAF else FolderAccess.DIRECT
            val fallback = if (useSaf) FolderAccess.DIRECT else FolderAccess.SAF
            val location = persistDirectoryAccess(dir, preferred) ?: persistDirectoryAccess(dir, fallback)
            if (location != null) onSetFolder(location)
        }
    SettingsHelpText(stringResource(Res.string.common_settings_watched_folder_help))
    Text(
        text = watchedFolder ?: stringResource(Res.string.common_settings_no_watched_folder),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch() }) {
            Text(text = stringResource(Res.string.common_settings_choose_folder))
        }
        if (watchedFolder != null) {
            OutlinedButton(onClick = { onSetFolder(null) }) {
                Text(text = stringResource(Res.string.common_settings_stop_watching))
            }
        }
    }
    // Only meaningful once a folder is watched: with no source folder there is nothing to remove.
    if (watchedFolder != null) {
        SettingsSwitchRow(
            title = stringResource(Res.string.common_settings_remove_after_import),
            help = stringResource(Res.string.common_settings_remove_after_import_help),
            checked = deleteAfterImport,
            onCheckedChange = onSetDeleteAfterImport,
        )
    }
}
