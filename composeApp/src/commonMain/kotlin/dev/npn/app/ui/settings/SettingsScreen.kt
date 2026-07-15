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
import androidx.compose.material3.Button
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.di.AppEnvironment
import dev.npn.core.app.ViewType

const val NPN_VERSION = "0.1.0"

@Composable
fun SettingsScreen(
    env: AppEnvironment,
    onBack: () -> Unit,
    onChangeLibrary: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(env.settings) }
    val state by vm.state.collectAsStateWithLifecycle()

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
            // KANBAN is deferred to a later wave; only offer LIST/GRID as a default.
            listOf(ViewType.LIST, ViewType.GRID).forEach { vt ->
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

private fun viewTypeLabel(v: ViewType): String = when (v) {
    ViewType.LIST -> "List"
    ViewType.GRID -> "Grid"
    ViewType.KANBAN -> "Kanban"
}
