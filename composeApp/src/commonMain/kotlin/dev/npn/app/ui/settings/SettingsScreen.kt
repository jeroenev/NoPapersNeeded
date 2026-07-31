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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.persistDirectoryAccess
import dev.npn.app.ui.common.viewTypeLabel
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.launch

const val NPN_VERSION = "0.1.0"

@Composable
fun SettingsScreen(
    env: AppEnvironment,
    session: LibrarySession,
    onBack: () -> Unit,
    onChangeLibrary: () -> Unit,
    onAutoMatchRules: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(env.settings, session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val reviewMode by vm.reviewMode.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            SectionHeader("Library")
            Text(
                text = state.libraryLocation ?: "No library selected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onChangeLibrary) { Text("Change library") }

            HorizontalDivider()

            SectionHeader("Watched folder")
            WatchedFolderSection(
                watchedFolder = state.watchedFolder,
                deleteAfterImport = state.watchedFolderDeleteAfterImport,
                onSetFolder = vm::setWatchedFolder,
                onSetDeleteAfterImport = vm::setWatchedFolderDeleteAfterImport,
            )

            HorizontalDivider()

            SectionHeader("OCR")
            OutlinedTextField(
                value = state.ocrLanguage,
                onValueChange = vm::setOcrLanguage,
                label = { Text("OCR language code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Tesseract/MLKit language code, e.g. eng, nld, fra.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionHeader("AI assistant")
            AiAssistantSection(
                enabled = state.aiEnabled,
                baseUrl = state.aiBaseUrl,
                apiKey = state.aiApiKey,
                model = state.aiModel,
                onSetEnabled = vm::setAiEnabled,
                onSetBaseUrl = vm::setAiBaseUrl,
                onSetApiKey = vm::setAiApiKey,
                onSetModel = vm::setAiModel,
            )

            HorizontalDivider()

            SectionHeader("Trash")
            var retentionText by remember(state.trashRetentionDays) {
                mutableStateOf(state.trashRetentionDays.toString())
            }
            OutlinedTextField(
                value = retentionText,
                onValueChange = { new ->
                    retentionText = new.filter { it.isDigit() }.take(4)
                    retentionText.toIntOrNull()?.let(vm::setTrashRetentionDays)
                },
                label = { Text("Trash retention (days)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            SectionHeader("Views")
            DefaultViewTypeDropdown(
                current = state.defaultViewType,
                onSelect = vm::setDefaultViewType,
            )
            Text(
                "Grid size: pinch the grid, use its zoom buttons, or Ctrl+scroll on desktop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LandscapeListToggle(
                enabled = state.landscapeListColumns,
                onToggle = vm::setLandscapeListColumns,
            )

            HorizontalDivider()

            SectionHeader("Storage")
            StoragePathRuleEditor(
                template = state.storagePathTemplate,
                enabled = state.storagePathEnabled,
                preview = state.storagePathPreview,
                error = state.storagePathError,
                onTemplateChange = vm::setStoragePathTemplate,
                onEnabledChange = vm::setStoragePathEnabled,
            )
            OutlinedButton(onClick = {
                vm.clearThumbnailCache { count ->
                    scope.launch {
                        snackbar.showSnackbar(
                            if (count > 0) "Cleared $count thumbnail(s)" else "Thumbnail cache already empty",
                        )
                    }
                }
            }) { Text("Clear thumbnail cache") }
            Text(
                "Regenerate library thumbnails at the current quality. Use this if previews look blurry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionHeader("Review workflow")
            InboxWorkflowToggle(
                current = reviewMode,
                onUntaggedOnly = vm::setUntaggedOnlyMode,
                onInboxWorkflow = vm::setInboxWorkflowMode,
            )

            HorizontalDivider()

            SectionHeader("Auto-matching")
            OutlinedButton(onClick = onAutoMatchRules) { Text("Auto-matching rules") }
            Text(
                "Automatically tag documents and set metadata when imported text matches a rule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionHeader("About")
            Text(
                "No papers needed $NPN_VERSION",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Personal, open-source paperless document app. Files are the source of truth.",
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
    error: String?,
    onTemplateChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Organize document paths automatically", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Moves PDFs after import and metadata changes; files are never overwritten.",
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
            label = { Text("Path template") },
            supportingText = {
                Text(error ?: "Preview: $preview")
            },
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Variables: {title}, {doc_date}, {doc_date:yyyy-MM}, {docType}, " +
                "{correspondent}, {tags}. Empty variable folders are omitted.",
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
    val picker = rememberDirectoryPickerLauncher { dir ->
        if (dir != null) onSetFolder(persistDirectoryAccess(dir))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "PDFs saved to this folder (for example by a scanner app) are imported into the " +
                "library automatically and OCR-ed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = watchedFolder ?: "No folder watched",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch() }) { Text("Choose folder") }
            if (watchedFolder != null) {
                OutlinedButton(onClick = { onSetFolder(null) }) { Text("Stop watching") }
            }
        }
        if (watchedFolder != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remove files after import", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "When on, source files are deleted from the watched folder once they are " +
                            "safely in the library. When off, they stay and are simply skipped as " +
                            "duplicates on later scans.",
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
private fun AiAssistantSection(
    enabled: Boolean,
    baseUrl: String,
    apiKey: String,
    model: String,
    onSetEnabled: (Boolean) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onSetApiKey: (String) -> Unit,
    onSetModel: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enable AI assistant", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Uses an OpenAI-compatible server to suggest tags and answer questions " +
                        "about your documents.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onSetEnabled)
        }
        if (enabled) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onSetBaseUrl,
                label = { Text("Server URL") },
                supportingText = {
                    Text(
                        "OpenAI-compatible server, e.g. https://api.openai.com or " +
                            "http://localhost:11434. A pasted /v1/chat/completions URL also works.",
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onSetApiKey,
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    Text("Stored only on this device; leave empty for local servers")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = onSetModel,
                label = { Text("Model") },
                supportingText = {
                    Text(
                        "Model id exactly as the server lists it (see its /v1/models), " +
                            "e.g. gpt-4.1-mini or qwen3:8b. Client-side provider prefixes do not apply.",
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Requests send document text to the configured server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun DefaultViewTypeDropdown(current: ViewType, onSelect: (ViewType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Default view: ${viewTypeLabel(current)}")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ViewType.entries.forEach { vt ->
                DropdownMenuItem(
                    text = { Text(viewTypeLabel(vt)) },
                    onClick = {
                        onSelect(vt)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LandscapeListToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Multi-column list in landscape", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Show the list view in two columns when the window is wider than it is tall.",
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
                Text("Enable inbox workflow", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "New documents land in an inbox and you mark them reviewed by hand. " +
                        "When off, documents just appear and count as reviewed once tagged.",
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
            "This setting syncs across your devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showMigration) {
        AlertDialog(
            onDismissRequest = { showMigration = false },
            title = { Text("Enable inbox workflow") },
            text = {
                Text(
                    "How should existing documents be marked? New documents will always start as " +
                        "needing review.",
                )
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        onInboxWorkflow(ReviewMigration.MARK_ALL); showMigration = false
                    }) { Text("Mark all as reviewed") }
                    TextButton(onClick = {
                        onInboxWorkflow(ReviewMigration.MARK_TAGGED); showMigration = false
                    }) { Text("Mark tagged documents as reviewed") }
                    TextButton(onClick = {
                        onInboxWorkflow(ReviewMigration.MARK_NONE); showMigration = false
                    }) { Text("Don't mark any (all need review)") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMigration = false }) { Text("Cancel") }
            },
        )
    }
}
