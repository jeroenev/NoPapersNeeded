package dev.npn.app.ui.ai

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.ExpandIcon
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.expander
import dev.npn.core.ai.AiChatSession
import dev.npn.core.ai.ChatTools
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_ask_documents
import no_papers_needed.composeapp.generated.resources.ai_assistant_title
import no_papers_needed.composeapp.generated.resources.ai_chat_history
import no_papers_needed.composeapp.generated.resources.ai_clear_conversation
import no_papers_needed.composeapp.generated.resources.ai_configure_in_settings
import no_papers_needed.composeapp.generated.resources.ai_delete_chat
import no_papers_needed.composeapp.generated.resources.ai_new_chat
import no_papers_needed.composeapp.generated.resources.ai_no_output
import no_papers_needed.composeapp.generated.resources.ai_open_all_documents
import no_papers_needed.composeapp.generated.resources.ai_send
import no_papers_needed.composeapp.generated.resources.ai_stop
import no_papers_needed.composeapp.generated.resources.ai_tool_listed_tagged_documents
import no_papers_needed.composeapp.generated.resources.ai_tool_listed_tags
import no_papers_needed.composeapp.generated.resources.ai_tool_read_document
import no_papers_needed.composeapp.generated.resources.ai_tool_read_document_text
import no_papers_needed.composeapp.generated.resources.ai_tool_searched_documents
import no_papers_needed.composeapp.generated.resources.ai_tool_searched_file_names
import no_papers_needed.composeapp.generated.resources.ai_unknown_value
import no_papers_needed.composeapp.generated.resources.ai_unspecified_document
import no_papers_needed.composeapp.generated.resources.library_ai_edit
import org.jetbrains.compose.resources.stringResource

private const val DOC_REF_LABEL_HEX = 8
private const val BUBBLE_MAX_WIDTH_FRACTION = 0.85f

@Composable
fun AiChatScreen(
    session: LibrarySession,
    settings: SettingsRepository,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onOpenFiltered: (List<String>) -> Unit,
    onAiEditDocs: (List<String>) -> Unit,
    showAiEdit: Boolean,
    /** Hands off to the AI-edit page without preselection. */
    onAiEdit: () -> Unit,
    vm: AiChatViewModel = viewModel { AiChatViewModel(session, settings) },
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val items = ui.transcript.items
    val lastLen = (items.lastOrNull() as? TranscriptItem.AssistantTurn)?.text?.length ?: 0
    val historySessions = ui.sessions.filterNot { it.id == ui.currentSessionId && items.isEmpty() }

    // Keep new output visible by following the bottom — but only while the user is already there,
    // so a position they scrolled to (or one restored by navigation/process death, which shows up
    // as a non-zero first visible index before the transcript loads) is never yanked away.
    LaunchedEffect(items.size, lastLen) {
        if (items.isEmpty()) return@LaunchedEffect
        val lastVisible =
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
        val atBottom = lastVisible?.let { it >= items.size - 2 } ?: (listState.firstVisibleItemIndex == 0)
        if (atBottom) listState.animateScrollToItem(items.size - 1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AiChatTopBar(
                title = chatTitle(ui.currentSessionId, historySessions),
                clearEnabled = items.isNotEmpty() && !ui.busy,
                newSessionEnabled = !ui.busy,
                onBack = onBack,
                onClear = vm::clear,
                onNewSession = vm::newSession,
                onAiEdit = onAiEdit.takeIf { showAiEdit },
            ) {
                ChatHistoryMenu(
                    sessions = historySessions,
                    currentSessionId = ui.currentSessionId,
                    onSession = vm::switchSession,
                    onDeleteSession = vm::deleteSession,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // The progress bar only covers the pre-first-token phase; once text streams the
            // growing answer is the progress indicator.
            if (ui.busy && !hasStreamingText(items)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Keyed by session and item, so a row's own state (an expanded tool row) stays with
                // its item, and never carries over to whatever sits at that position in another chat.
                items(items, key = { "${ui.currentSessionId}:${it.id}" }) { item ->
                    when (item) {
                        is TranscriptItem.UserTurn -> {
                            UserBubble(item)
                        }

                        is TranscriptItem.AssistantTurn -> {
                            // Skip a finished, empty assistant turn entirely (no blank bubble); a
                            // still-streaming turn may legitimately be empty before the first token
                            // and keeps rendering so the in-progress bubble is visible.
                            if (item.text.isNotEmpty() || item.streaming) {
                                AssistantBubble(item, onOpenDoc, onOpenFiltered, onAiEditDocs, ui.docTitles)
                            }
                        }

                        is TranscriptItem.ToolUse -> {
                            ToolRow(item)
                        }
                    }
                }
            }

            when (ui.configured) {
                false -> {
                    ConfigureHint()
                }

                else -> {
                    InputRow(
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
}

/** The assistant, or the open chat by its title; a chat is untitled until its first question names it. */
@Composable
private fun chatTitle(
    currentSessionId: String?,
    sessions: List<AiChatSession>,
): String =
    if (currentSessionId == null) {
        stringResource(Res.string.ai_assistant_title)
    } else {
        sessions.firstOrNull { it.id == currentSessionId }?.title ?: stringResource(Res.string.ai_new_chat)
    }

/** Back, then the chat's actions; [historyMenu] sits between starting a chat and the AI edit hand-off. */
@Composable
private fun AiChatTopBar(
    title: String,
    clearEnabled: Boolean,
    newSessionEnabled: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onNewSession: () -> Unit,
    /** Hands off to the AI-edit page; null when that page is not offered. */
    onAiEdit: (() -> Unit)?,
    historyMenu: @Composable () -> Unit,
) {
    BackTopBar(title = title, onBack = onBack) {
        TooltipIconButton(
            icon = Icons.Outlined.Add,
            label = stringResource(Res.string.ai_new_chat),
            onClick = onNewSession,
            enabled = newSessionEnabled,
        )
        historyMenu()
        if (onAiEdit != null) {
            TooltipIconButton(
                icon = Icons.Outlined.AutoFixHigh,
                label = stringResource(Res.string.library_ai_edit),
                onClick = onAiEdit,
            )
        }
        TooltipIconButton(
            icon = Icons.Filled.DeleteSweep,
            label = stringResource(Res.string.ai_clear_conversation),
            onClick = onClear,
            enabled = clearEnabled,
        )
    }
}

/** Past chats behind the history button: open one, or delete it. The open chat is in bold. */
@Composable
private fun ChatHistoryMenu(
    sessions: List<AiChatSession>,
    currentSessionId: String?,
    onSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(
            icon = Icons.Outlined.History,
            label = stringResource(Res.string.ai_chat_history),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sessions.forEach { session ->
                DropdownMenuItem(
                    text = {
                        Text(
                            session.title ?: stringResource(Res.string.ai_new_chat),
                            fontWeight = if (session.id == currentSessionId) FontWeight.Bold else null,
                            maxLines = 1,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSession(session.id)
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            expanded = false
                            onDeleteSession(session.id)
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(Res.string.ai_delete_chat))
                        }
                    },
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
private fun AssistantBubble(
    item: TranscriptItem.AssistantTurn,
    onOpenDoc: (String) -> Unit,
    onOpenFiltered: (List<String>) -> Unit,
    onAiEditDocs: (List<String>) -> Unit,
    docTitles: Map<String, String>,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Bubble(fromUser = false) {
            Text(
                item.text.ifEmpty { " " },
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (item.docRefs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { onOpenFiltered(item.docRefs) },
                    label = { Text(stringResource(Res.string.ai_open_all_documents)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
                AssistChip(
                    onClick = { onAiEditDocs(item.docRefs) },
                    label = { Text(stringResource(Res.string.library_ai_edit)) },
                    leadingIcon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null) },
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(BUBBLE_MAX_WIDTH_FRACTION).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.docRefs.forEach { docId ->
                    AssistChip(
                        onClick = { onOpenDoc(docId) },
                        label = {
                            Text(docTitles[docId] ?: docId.take(DOC_REF_LABEL_HEX))
                        },
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
private fun Bubble(
    fromUser: Boolean,
    content: @Composable () -> Unit,
) {
    val alignment = if (fromUser) Alignment.End else Alignment.Start
    val bubbleColor =
        if (fromUser) {
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
            // One container per bubble, not one around the whole list: a LazyColumn disposes
            // scrolled-off items, which would drop an in-progress cross-bubble selection.
            SelectionContainer { content() }
        }
    }
}

@Composable
private fun ToolRow(item: TranscriptItem.ToolUse) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .expander(expanded, enabled = !item.running) { expanded = !expanded }
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
                ExpandIcon(
                    expanded,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded && !item.running) {
            val resultText =
                if (item.result.isEmpty()) {
                    stringResource(Res.string.ai_no_output)
                } else {
                    item.result
                }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                SelectionContainer {
                    Text(
                        resultText,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
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
    onStop: () -> Unit,
) {
    // Plain Enter sends (and is consumed so no newline is inserted); Shift+Enter inserts one.
    val send = { if (!busy && value.isNotBlank()) onSend() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            false
                        } else if (event.key == Key.Enter && !event.isShiftPressed) {
                            send()
                            true
                        } else {
                            false
                        }
                    },
            placeholder = { Text(stringResource(Res.string.ai_ask_documents)) },
            enabled = !busy,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        if (busy) {
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource(Res.string.ai_stop))
            }
        } else {
            IconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(Res.string.ai_send))
            }
        }
    }
}

@Composable
private fun ConfigureHint() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(Res.string.ai_configure_in_settings),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun toolIcon(name: String): ImageVector =
    when (name) {
        ChatTools.SEARCH_DOCUMENTS, ChatTools.SEARCH_FILENAMES -> Icons.Outlined.Search
        ChatTools.GET_DOCUMENT, ChatTools.GET_DOCUMENT_TEXT -> Icons.Outlined.Description
        ChatTools.LIST_TAGS, ChatTools.DOCUMENTS_WITH_TAG -> Icons.AutoMirrored.Outlined.Label
        else -> Icons.Outlined.Info
    }

/**
 * A human-readable label for a tool invocation, pulling the salient argument out of the raw
 * arguments JSON. Kept lenient: on any parse miss it falls back to a plain verb so a row is never
 * blank. Lives in the UI layer since it is purely presentational.
 */
@Composable
internal fun toolLabel(
    name: String,
    argsJson: String,
): String =
    when (name) {
        ChatTools.SEARCH_DOCUMENTS -> {
            stringResource(
                Res.string.ai_tool_searched_documents,
                quoted(argValue(argsJson, ChatTools.ARG_QUERY)),
            )
        }

        ChatTools.SEARCH_FILENAMES -> {
            stringResource(
                Res.string.ai_tool_searched_file_names,
                quoted(argValue(argsJson, ChatTools.ARG_PATTERN)),
            )
        }

        ChatTools.DOCUMENTS_WITH_TAG -> {
            stringResource(
                Res.string.ai_tool_listed_tagged_documents,
                quoted(argValue(argsJson, ChatTools.ARG_TAG)),
            )
        }

        ChatTools.LIST_TAGS -> {
            stringResource(Res.string.ai_tool_listed_tags)
        }

        ChatTools.GET_DOCUMENT -> {
            stringResource(
                Res.string.ai_tool_read_document,
                shortId(argValue(argsJson, ChatTools.ARG_DOC_ID)),
            )
        }

        ChatTools.GET_DOCUMENT_TEXT -> {
            stringResource(
                Res.string.ai_tool_read_document_text,
                shortId(argValue(argsJson, ChatTools.ARG_DOC_ID)),
            )
        }

        else -> {
            name.replace('_', ' ')
        }
    }

private fun argValue(
    argsJson: String,
    key: String,
): String? =
    runCatching {
        Json
            .parseToJsonElement(argsJson)
            .jsonObject[key]
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

@Composable
private fun quoted(value: String?): String = value?.let { "\"$it\"" } ?: stringResource(Res.string.ai_unknown_value)

@Composable
private fun shortId(value: String?): String =
    value?.take(DOC_REF_LABEL_HEX)?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.ai_unspecified_document)
