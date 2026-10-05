@file:OptIn(ExperimentalLayoutApi::class)

package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.npn.app.platform.FolderAccess
import dev.npn.app.platform.ocrHasLanguageSetting
import dev.npn.app.platform.persistDirectoryAccess
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.LocalAppLocale
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.common.ocrLanguageName
import dev.npn.app.ui.common.rememberStorageAccess
import dev.npn.core.ocr.OcrLanguages
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching_help
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching_rules
import no_papers_needed.composeapp.generated.resources.common_settings_choose_folder
import no_papers_needed.composeapp.generated.resources.common_settings_no_watched_folder
import no_papers_needed.composeapp.generated.resources.common_settings_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_add_language
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_android_help
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_languages_help
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_match_app_language
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_match_app_language_help
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_other_language
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_other_language_help
import no_papers_needed.composeapp.generated.resources.common_settings_remove_after_import
import no_papers_needed.composeapp.generated.resources.common_settings_remove_after_import_help
import no_papers_needed.composeapp.generated.resources.common_settings_stop_watching
import no_papers_needed.composeapp.generated.resources.common_settings_watched_folder
import no_papers_needed.composeapp.generated.resources.common_settings_watched_folder_help
import org.jetbrains.compose.resources.stringResource

/** Example codes shown with the other-language field: languages the picker does not list. */
private const val OTHER_OCR_LANGUAGE_EXAMPLES = "ita, pol"

/**
 * How documents get into the library and what happens to them on the way in: the watched folder,
 * text recognition, and the rules that fill in metadata from the recognised text.
 */
@Composable
internal fun ImportSettingsPane(context: SettingsPaneContext) {
    SettingsSection(title = stringResource(Res.string.common_settings_watched_folder)) {
        WatchedFolderSettings(
            watchedFolder = context.state.snapshot.watchedFolder,
            useSaf = context.state.snapshot.useSafStorage,
            deleteAfterImport = context.state.snapshot.watchedFolderDeleteAfterImport,
            onSetFolder = { folder -> context.vm.edit { it.copy(watchedFolder = folder) } },
            onSetDeleteAfterImport = { on -> context.vm.edit { it.copy(watchedFolderDeleteAfterImport = on) } },
        )
    }

    SettingsSection(title = stringResource(Res.string.common_settings_ocr)) {
        if (ocrHasLanguageSetting()) {
            OcrLanguageSettings(
                setting = context.state.snapshot.ocrLanguage,
                onChange = { value -> context.vm.edit { it.copy(ocrLanguage = value) } },
            )
        } else {
            HelpText(stringResource(Res.string.common_settings_ocr_android_help))
        }
    }

    SettingsSection(title = stringResource(Res.string.common_settings_auto_matching)) {
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
    val storage = rememberStorageAccess()
    val picker =
        rememberDirectoryPickerLauncher { dir ->
            if (dir == null) return@rememberDirectoryPickerLauncher
            val preferSaf = useSaf || !storage.usable
            val preferred = if (preferSaf) FolderAccess.SAF else FolderAccess.DIRECT
            val fallback = if (preferSaf) FolderAccess.DIRECT else FolderAccess.SAF
            val location =
                persistDirectoryAccess(dir, preferred)
                    ?: if (fallback == FolderAccess.SAF || storage.usable) {
                        persistDirectoryAccess(dir, fallback)
                    } else {
                        null
                    }
            if (location != null) onSetFolder(location)
        }
    HelpText(stringResource(Res.string.common_settings_watched_folder_help))
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
        SwitchRow(
            label = stringResource(Res.string.common_settings_remove_after_import),
            help = stringResource(Res.string.common_settings_remove_after_import_help),
            checked = deleteAfterImport,
            onCheckedChange = onSetDeleteAfterImport,
        )
    }
}

/**
 * Which languages OCR recognises: by default the app language plus English, otherwise any set the
 * user picks. Chips cover the bundled models; a code typed below adds any other installed one,
 * and shows up as a chip of its own so it can be removed again.
 */
@Composable
private fun OcrLanguageSettings(
    setting: String?,
    onChange: (String?) -> Unit,
) {
    val automatic = setting == null
    val selected = setting?.let(OcrLanguages::parse) ?: OcrLanguages.forAppLanguage(LocalAppLocale.current)
    SwitchRow(
        label = stringResource(Res.string.common_settings_ocr_match_app_language),
        help = stringResource(Res.string.common_settings_ocr_match_app_language_help),
        checked = automatic,
        // Switching off starts from what automatic used, so nothing changes until a chip is tapped.
        onCheckedChange = { on -> onChange(if (on) null else OcrLanguages.format(selected)) },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (OcrLanguages.BUNDLED + selected).distinct().forEach { code ->
            val isSelected = code in selected
            FilterChip(
                selected = isSelected,
                enabled = !automatic,
                onClick = {
                    // OCR needs at least one language, so the last one cannot be deselected.
                    if (isSelected && selected.size == 1) return@FilterChip
                    onChange(OcrLanguages.format(if (isSelected) selected - code else selected + code))
                },
                label = { Text(text = ocrLanguageName(code)) },
                leadingIcon =
                    if (isSelected) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
    HelpText(stringResource(Res.string.common_settings_ocr_languages_help))
    if (!automatic) {
        var other by remember { mutableStateOf("") }
        val add = {
            val codes = OcrLanguages.parse(other)
            if (codes.isNotEmpty()) onChange(OcrLanguages.format((selected + codes).distinct()))
            other = ""
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = other,
                onValueChange = { other = it },
                label = { Text(text = stringResource(Res.string.common_settings_ocr_other_language)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = add, enabled = other.isNotBlank()) {
                Text(text = stringResource(Res.string.common_settings_ocr_add_language))
            }
        }
        HelpText(
            stringResource(Res.string.common_settings_ocr_other_language_help, OTHER_OCR_LANGUAGE_EXAMPLES),
        )
    }
}
