@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.core.app.LibrarySession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm, snackbar) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lastLen = (ui.turns.lastOrNull() as? TranscriptItem.AssistantTurn)?.text?.length ?: 0

    // Keep the newest turn visible as the transcript grows or the streaming answer lengthens.
    LaunchedEffect(ui.turns.size, lastLen) {
        if (ui.turns.isNotEmpty()) listState.animateScrollToItem(ui.turns.size - 1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
            // The progress bar only covers the pre-first-token phase; once text streams the
            // growing answer is the progress indicator.
            if (ui.busy && !hasStreamingText(ui.turns)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.turns) { item ->
                    when (item) {
                        is TranscriptItem.UserTurn -> UserBubble(item)
                        is TranscriptItem.AssistantTurn ->
                            // Skip a finished, empty assistant turn entirely (no blank bubble); a
                            // still-streaming turn may legitimately be empty before the first token
                            // and keeps rendering so the in-progress bubble is visible.
                            if (item.text.isNotEmpty() || item.streaming) AssistantBubble(item, onOpenDoc)
                        is TranscriptItem.ToolUse -> ToolRow(item)
                    }
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
                    onStop = vm::stop,
                )
            }
        }
    }
}

private fun hasStreamingText(turns: List<TranscriptItem>): Boolean =
    (turns.lastOrNull() as? TranscriptItem.AssistantTurn)?.let {
        it.streaming && it.text.isNotEmpty()
    } ?: false

@Composable
private fun UserBubble(item: TranscriptItem.UserTurn) {
    Bubble(fromUser = true) {
        Text(item.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AssistantBubble(item: TranscriptItem.AssistantTurn, onOpenDoc: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Bubble(fromUser = false) {
            Text(
                item.text.ifEmpty { " " },
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
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
private fun Bubble(fromUser: Boolean, content: @Composable () -> Unit) {
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
            content()
        }
    }
}

@Composable
private fun ToolRow(item: TranscriptItem.ToolUse) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !item.running) { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                toolIcon(item.name),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                toolLabel(item.name, item.argsSummary),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.running) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded && !item.running) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text(
                    item.result.ifEmpty { "(no output)" },
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
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
    onStop: () -> Unit,
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
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
            }
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

private fun toolIcon(name: String): ImageVector = when (name) {
    "search_documents", "search_filenames" -> Icons.Outlined.Search
    "get_document", "get_document_text" -> Icons.Outlined.Description
    "list_tags", "documents_with_tag" -> Icons.AutoMirrored.Outlined.Label
    else -> Icons.Outlined.Info
}

/**
 * A human-readable label for a tool invocation, pulling the salient argument out of the raw
 * arguments JSON. Kept lenient: on any parse miss it falls back to a plain verb so a row is never
 * blank. Lives in the UI layer since it is purely presentational.
 */
internal fun toolLabel(name: String, argsJson: String): String = when (name) {
    "search_documents" -> "Searched documents: ${quoted(argValue(argsJson, "query"))}"
    "search_filenames" -> "Searched file names: ${quoted(argValue(argsJson, "pattern"))}"
    "documents_with_tag" -> "Listed documents tagged ${quoted(argValue(argsJson, "tag"))}"
    "list_tags" -> "Listed tags"
    "get_document" -> "Read document ${shortId(argValue(argsJson, "docId"))}"
    "get_document_text" -> "Read text of ${shortId(argValue(argsJson, "docId"))}"
    else -> name.replace('_', ' ')
}

private fun argValue(argsJson: String, key: String): String? =
    runCatching {
        Json.parseToJsonElement(argsJson).jsonObject[key]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

private fun quoted(value: String?): String = value?.let { "\"$it\"" } ?: "(unknown)"

private fun shortId(value: String?): String =
    value?.take(DOC_REF_LABEL_HEX)?.takeIf { it.isNotBlank() } ?: "a document"
