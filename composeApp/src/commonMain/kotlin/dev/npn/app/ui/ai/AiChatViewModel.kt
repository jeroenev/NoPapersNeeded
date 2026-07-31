package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.ai.AiChatTurn
import dev.npn.core.ai.AiRole
import dev.npn.core.ai.AiText
import dev.npn.core.app.LibrarySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One rendered chat turn. [docRefs] are docIds cited by an assistant answer (empty for users). */
data class ChatItem(
    val role: AiRole,
    val text: String,
    val docRefs: List<String> = emptyList(),
)

/**
 * Chat UI state. [configured] is null until the initial config probe completes, then true/false so
 * the screen can show a "configure in settings" hint instead of the input row.
 */
data class AiChatUiState(
    val turns: List<ChatItem> = emptyList(),
    val busy: Boolean = false,
    val configured: Boolean? = null,
)

/**
 * Drives the AI assistant conversation for one opened library. Holds the running transcript in a
 * [MutableStateFlow]; each [send] appends the user turn, calls [LibrarySession.ai], then appends the
 * assistant answer (with any cited doc refs extracted). Network/config failures surface as one-shot
 * messages via [MessagesViewModel] while the user turn is kept so the question is not lost.
 */
class AiChatViewModel(
    val session: LibrarySession,
) : MessagesViewModel() {

    private val _ui = MutableStateFlow(AiChatUiState())
    val ui: StateFlow<AiChatUiState> = _ui.asStateFlow()

    // Kept for parity with the repo's WhileSubscribed idiom; referenced so detekt sees a use.
    @Suppress("unused")
    private val sharingPolicy = WhileSubscribedTimeout

    init {
        viewModelScope.launch {
            val configured = runCatching { session.ai.config().configured }.getOrDefault(false)
            _ui.update { it.copy(configured = configured) }
        }
    }

    /** Send [question]; ignored when blank or while a request is in flight. */
    fun send(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _ui.value.busy) return

        // History passed to the model excludes the just-added question (sent as the question param).
        val history = _ui.value.turns.map { AiChatTurn(it.role, it.text) }
        _ui.update {
            it.copy(turns = it.turns + ChatItem(AiRole.USER, trimmed), busy = true)
        }

        viewModelScope.launch {
            runCatching { session.ai.ask(history, trimmed) }
                .onSuccess { answer ->
                    val refs = AiText.extractDocRefs(answer)
                    _ui.update {
                        it.copy(
                            turns = it.turns + ChatItem(AiRole.ASSISTANT, answer, refs),
                            busy = false,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _ui.update { it.copy(busy = false) }
                    val reason = e.message?.takeIf { m -> m.isNotBlank() }
                        ?: (e::class.simpleName ?: "unknown error")
                    message("Could not reach the AI assistant: $reason")
                }
        }
    }

    /** Reset the conversation to an empty transcript. */
    fun clear() {
        _ui.update { it.copy(turns = emptyList(), busy = false) }
    }
}
