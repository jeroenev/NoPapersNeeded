package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.isConfiguredOrFalse
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatTurn
import dev.npn.core.ai.AiFacade
import dev.npn.core.ai.AiRole
import dev.npn.core.ai.AiText
import dev.npn.core.app.LibrarySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One rendered item in the chat transcript: a user turn, a tool use, or an assistant turn. */
sealed interface TranscriptItem {
    /** A question the user asked. */
    data class UserTurn(
        val text: String,
    ) : TranscriptItem

    /**
     * One tool invocation. [argsSummary] is the raw arguments JSON (the UI maps it to a label);
     * [result] is empty while [running] and filled once the tool finished.
     */
    data class ToolUse(
        val name: String,
        val argsSummary: String,
        val result: String,
        val running: Boolean,
    ) : TranscriptItem

    /** An assistant answer; [text] grows while [streaming], [docRefs] filled once complete. */
    data class AssistantTurn(
        val text: String,
        val docRefs: List<String>,
        val streaming: Boolean,
    ) : TranscriptItem
}

/**
 * Chat UI state. [configured] is null until the initial config probe completes, then true/false so
 * the screen can show a "configure in settings" hint instead of the input row. [busy] is true while
 * a request is streaming.
 */
data class AiChatUiState(
    val turns: List<TranscriptItem> = emptyList(),
    val busy: Boolean = false,
    val configured: Boolean? = null,
)

/**
 * Drives the AI assistant conversation for one opened library. Holds the running transcript in a
 * [MutableStateFlow]; [send] launches a collection job that folds [AiChatEvent]s into
 * [AiChatUiState.turns] — text deltas grow the trailing [TranscriptItem.AssistantTurn], tool events
 * insert and complete [TranscriptItem.ToolUse] items in order. History for the next `ask` is the
 * user/assistant text turns only. Network/config failures keep the partial answer and surface a
 * one-shot message via [MessagesViewModel]. [stop] cancels the running job and finalises the
 * partial assistant turn so it stays in history.
 */
class AiChatViewModel(
    private val ai: AiFacade,
) : MessagesViewModel() {
    constructor(session: LibrarySession) : this(session.ai)

    private val _ui = MutableStateFlow(AiChatUiState())
    val ui: StateFlow<AiChatUiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            val configured = ai.isConfiguredOrFalse()
            _ui.update { it.copy(configured = configured) }
        }
    }

    /** Send [question]; ignored when blank or while a request is in flight. */
    fun send(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _ui.value.busy) return

        val history = historyForAsk()
        _ui.update {
            it.copy(
                turns = it.turns + TranscriptItem.UserTurn(trimmed),
                busy = true,
            )
        }

        job =
            viewModelScope.launch {
                runCatching { collectAnswer(history, trimmed) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        finishStreaming()
                        _ui.update { it.copy(busy = false) }
                        message(uiText(UiTextKey.AI_ASSISTANT_UNREACHABLE, reasonOf(e)))
                    }
            }
    }

    /** Stop a streaming request: finalise the partial assistant turn (kept in history). */
    fun stop() {
        job?.cancel()
        job = null
        finishStreaming()
        _ui.update { it.copy(busy = false) }
    }

    /** Reset the conversation to an empty transcript. */
    fun clear() {
        job?.cancel()
        job = null
        _ui.update { it.copy(turns = emptyList(), busy = false) }
    }

    private suspend fun collectAnswer(
        history: List<AiChatTurn>,
        question: String,
    ) {
        ai.ask(history, question).collect { event -> fold(event) }
        _ui.update { it.copy(busy = false) }
    }

    private fun fold(event: AiChatEvent) {
        when (event) {
            is AiChatEvent.TextDelta -> appendDelta(event.text)
            is AiChatEvent.ToolCallStarted -> startTool(event.name, event.argsJson)
            is AiChatEvent.ToolCallFinished -> finishTool(event.name, event.result)
            is AiChatEvent.Done -> completeAssistant(event.fullText)
        }
    }

    private fun appendDelta(delta: String) =
        _ui.update { state ->
            val last = state.turns.lastOrNull()
            if (last is TranscriptItem.AssistantTurn && last.streaming) {
                state.copy(turns = state.turns.dropLast(1) + last.copy(text = last.text + delta))
            } else {
                state.copy(
                    turns = state.turns + TranscriptItem.AssistantTurn(delta, emptyList(), streaming = true),
                )
            }
        }

    private fun startTool(
        name: String,
        argsJson: String,
    ) = _ui.update { state ->
        // A tool call ends the current narration turn: finalise the trailing streaming assistant
        // turn (extracting its own docRefs) so pre-tool text is committed once and never merged
        // with post-tool text or overwritten by the run's full answer later.
        state.copy(
            turns =
                finaliseTrailingAssistant(state.turns) +
                    TranscriptItem.ToolUse(name, argsJson, result = "", running = true),
        )
    }

    private fun finishTool(
        name: String,
        result: String,
    ) = _ui.update { state ->
        val index =
            state.turns.indexOfLast {
                it is TranscriptItem.ToolUse && it.name == name && it.running
            }
        if (index < 0) return@update state
        val tool = state.turns[index] as TranscriptItem.ToolUse
        val updated = state.turns.toMutableList()
        updated[index] = tool.copy(result = result, running = false)
        state.copy(turns = updated)
    }

    private fun completeAssistant(fullText: String) {
        _ui.update { state ->
            val last = state.turns.lastOrNull()
            if (last is TranscriptItem.AssistantTurn) {
                // Finalise ONLY the trailing turn using its own accumulated delta text; never
                // overwrite it with the run's full answer (which spans earlier, already-finalised
                // turns). docRefs are extracted from this turn's own text so each bubble links only
                // the documents it actually cited.
                state.copy(turns = finaliseTrailingAssistant(state.turns))
            } else {
                // No delta ever arrived (e.g. an answer produced entirely through the non-streaming
                // fallback that only emits Done): materialise the full answer as one finished turn.
                state.copy(
                    turns =
                        state.turns +
                            TranscriptItem.AssistantTurn(fullText, AiText.extractDocRefs(fullText), streaming = false),
                )
            }
        }
        // A run that ends blank with nothing visible (no text bubble, no tool rows) would leave no
        // trace at all — the finished empty bubble is skipped by the screen — so surface it.
        if (fullText.isBlank() && !hasVisibleContent()) {
            message(uiText(UiTextKey.AI_ASSISTANT_EMPTY_ANSWER))
        }
    }

    /** True when the transcript shows any tool row or any non-blank assistant text. */
    private fun hasVisibleContent(): Boolean =
        _ui.value.turns.any { item ->
            when (item) {
                is TranscriptItem.ToolUse -> true
                is TranscriptItem.AssistantTurn -> item.text.isNotBlank()
                is TranscriptItem.UserTurn -> false
            }
        }

    /** Mark any trailing still-streaming assistant turn as complete (keeps partial text). */
    private fun finishStreaming() =
        _ui.update { state ->
            state.copy(turns = finaliseTrailingAssistant(state.turns))
        }

    /**
     * If the trailing item is a still-streaming [TranscriptItem.AssistantTurn], mark it complete
     * (keeping its own accumulated text) and extract its docRefs; otherwise leave the list as-is.
     */
    private fun finaliseTrailingAssistant(turns: List<TranscriptItem>): List<TranscriptItem> {
        val last = turns.lastOrNull()
        return if (last is TranscriptItem.AssistantTurn && last.streaming) {
            turns.dropLast(1) + last.copy(docRefs = AiText.extractDocRefs(last.text), streaming = false)
        } else {
            turns
        }
    }

    /** History for the next ask: user/assistant text turns only (tool items excluded). */
    private fun historyForAsk(): List<AiChatTurn> =
        _ui.value.turns.mapNotNull { item ->
            when (item) {
                is TranscriptItem.UserTurn -> AiChatTurn(AiRole.USER, item.text)
                is TranscriptItem.AssistantTurn -> AiChatTurn(AiRole.ASSISTANT, item.text)
                is TranscriptItem.ToolUse -> null
            }
        }
}
