package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.isConfiguredOrFalse
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatTurn
import dev.npn.core.ai.AiFacade
import dev.npn.core.app.LibrarySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Chat UI state. [configured] is null until the initial config probe completes, then true/false so
 * the screen can show a "configure in settings" hint instead of the input row. [busy] is true while
 * a request is streaming.
 */
data class AiChatUiState(
    val transcript: ChatTranscript = ChatTranscript.EMPTY,
    val busy: Boolean = false,
    val configured: Boolean? = null,
)

/**
 * Drives the AI assistant conversation for one opened library. Holds the running [ChatTranscript]
 * in a [MutableStateFlow]; [send] launches a collection job that folds [AiChatEvent]s into the
 * transcript (see [ChatTranscript] for the fold rules). History for the next `ask` is the
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

        val history = _ui.value.transcript.history
        _ui.update {
            it.copy(
                transcript = it.transcript.plusUser(trimmed),
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
        _ui.update { it.copy(transcript = ChatTranscript.EMPTY, busy = false) }
    }

    private suspend fun collectAnswer(
        history: List<AiChatTurn>,
        question: String,
    ) {
        ai.ask(history, question).collect { event ->
            _ui.update { it.copy(transcript = it.transcript + event) }
            if (event is AiChatEvent.Done) {
                // A run that ends blank with nothing visible (no text bubble, no tool rows) would
                // leave no trace at all — the finished empty bubble is skipped by the screen — so
                // surface it. The check runs against the post-Done transcript.
                if (event.fullText.isBlank() && !_ui.value.transcript.hasVisibleContent) {
                    message(uiText(UiTextKey.AI_ASSISTANT_EMPTY_ANSWER))
                }
            }
        }
        _ui.update { it.copy(busy = false) }
    }

    /** Mark any trailing still-streaming assistant turn as complete (keeps partial text). */
    private fun finishStreaming() = _ui.update { it.copy(transcript = it.transcript.finalized()) }
}
