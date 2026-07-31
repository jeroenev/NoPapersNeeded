@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.core.ai.AiRole
import dev.npn.core.app.LibrarySession

private const val DOC_REF_LABEL_HEX = 8
private const val BUBBLE_MAX_WIDTH_FRACTION = 0.85f

@Composable
fun AiChatScreen(
    session: LibrarySession,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
) {
    val vm: AiChatViewModel = viewModel { AiChatViewModel(session) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Keep the newest turn visible as the transcript grows.
    LaunchedEffect(ui.turns.size) {
        if (ui.turns.isNotEmpty()) listState.animateScrollToItem(ui.turns.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI assistant") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = vm::clear,
                        enabled = ui.turns.isNotEmpty() && !ui.busy,
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear conversation")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.turns) { item ->
                    ChatBubble(item = item, onOpenDoc = onOpenDoc)
                }
            }

            when (ui.configured) {
                false -> ConfigureHint()
                else -> InputRow(
                    value = input,
                    busy = ui.busy,
                    onValueChange = { input = it },
                    onSend = {
                        vm.send(input)
                        input = ""
                    },
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(item: ChatItem, onOpenDoc: (String) -> Unit) {
    val fromUser = item.role == AiRole.USER
    val alignment = if (fromUser) Alignment.End else Alignment.Start
    val bubbleColor = if (fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(
            color = bubbleColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(BUBBLE_MAX_WIDTH_FRACTION),
        ) {
            Text(item.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if (item.docRefs.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(BUBBLE_MAX_WIDTH_FRACTION).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.docRefs.forEach { docId ->
                    AssistChip(
                        onClick = { onOpenDoc(docId) },
                        label = { Text("Open ${docId.take(DOC_REF_LABEL_HEX)}") },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InputRow(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask about your documents") },
            enabled = !busy,
            maxLines = 4,
        )
        if (busy) {
            CircularProgressIndicator(Modifier.padding(8.dp))
        } else {
            IconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun ConfigureHint() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Configure the AI assistant in Settings",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
