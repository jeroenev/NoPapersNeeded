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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.viewTypeLabel
import dev.npn.core.app.GridSizing
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode

const val NPN_VERSION = "0.1.0"

@Composable
fun SettingsScreen(
    env: AppEnvironment,
    session: LibrarySession,
    onBack: () -> Unit,
    onChangeLibrary: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(env.settings, session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val reviewMode by vm.reviewMode.collectAsStateWithLifecycle()

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
            GridSizeSlider(
                current = state.gridColumnWidth,
                onChange = vm::setGridColumnWidth,
            )
            LandscapeListToggle(
                enabled = state.landscapeListColumns,
                onToggle = vm::setLandscapeListColumns,
            )

            HorizontalDivider()

            SectionHeader("Review workflow")
            InboxWorkflowToggle(
                current = reviewMode,
                onUntaggedOnly = vm::setUntaggedOnlyMode,
                onInboxWorkflow = vm::setInboxWorkflowMode,
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
private fun GridSizeSlider(current: Int, onChange: (Int) -> Unit) {
    // Local slider position for smooth dragging; the committed value drives the label and persists.
    var position by remember(current) { mutableStateOf(current.toFloat()) }
    Column {
        Text(
            "Grid item size: ${position.toInt()} dp",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onChange(position.toInt()) },
            valueRange = GridSizing.MIN_COLUMN_WIDTH.toFloat()..GridSizing.MAX_COLUMN_WIDTH.toFloat(),
        )
        Text(
            "Smaller values fit more columns. Only affects the grid view.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
