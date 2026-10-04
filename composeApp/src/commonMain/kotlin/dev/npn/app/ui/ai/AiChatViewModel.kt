package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.isConfiguredOrFalse
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiChat
import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatSession
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.LibrarySession
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_chat_clear_failed
import no_papers_needed.composeapp.generated.resources.ai_chat_delete_failed
import no_papers_needed.composeapp.generated.resources.ai_chat_new_failed

/**
 * Chat UI state. [configured] is null until the initial config probe completes, then true/false so
 * the screen can show a "configure in settings" hint instead of the input row. [busy] is true while
 * a request is streaming. [docTitles] resolves cited docRefs to display titles as they appear in
 * the transcript; an unresolved ref falls back to a shortened id at the render site.
 */
data class AiChatUiState(
    val transcript: ChatTranscript = ChatTranscript.EMPTY,
    val sessions: List<AiChatSession> = emptyList(),
    val currentSessionId: String? = null,
    val busy: Boolean = false,
    val configured: Boolean? = null,
    val docTitles: Map<String, String> = emptyMap(),
)

/**
 * Drives the AI assistant conversation for one opened library. Holds the running [ChatTranscript]
 * in a [MutableStateFlow]; [send] launches a collection job that folds [AiChatEvent]s into the
 * transcript (see [ChatTranscript] for the fold rules). The transcript is display state only —
 * the next `ask` sees the history the session persisted, not this fold. Network/config failures
 * keep the partial answer and surface a one-shot message via [MessagesViewModel]. [stop] cancels
 * the running job and finalises the partial assistant turn so it stays in history.
 */
class AiChatViewModel(
    private val ai: AiChat,
    private val documents: DocumentRepository,
) : MessagesViewModel() {
    constructor(session: LibrarySession) : this(session.aiChat, session.documents)

    private val _ui = MutableStateFlow(AiChatUiState())
    val ui: StateFlow<AiChatUiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            val configured = ai.isConfiguredOrFalse()
            _ui.update { it.copy(configured = configured) }
        }
        viewModelScope.launch {
            val sessions = runCatchingCancellable { ai.chatSessions() }.getOrDefault(emptyList())
            val session =
                runCatchingCancellable { ai.createChatSession() }.getOrNull()
                    ?: sessions.firstOrNull()
                    ?: AiChatSession(LEGACY_SESSION_ID, "Assistant chat", 0L)
            _ui.update {
                it.copy(
                    sessions =
                        (listOf(session) + sessions).distinctBy { item ->
                            item.id
                        },
                    currentSessionId = session.id,
                )
            }
            loadSession(session.id)
        }
        // Resolve display titles for cited documents as they appear in the transcript, so the
        // bubbles can label their doc chips. Title lookup is data access and belongs here, not in
        // a leaf composable (where it would also re-fire as rows scroll in and out).
        viewModelScope.launch {
            _ui
                .map { state ->
                    state.transcript.items
                        .filterIsInstance<TranscriptItem.AssistantTurn>()
                        .flatMap { it.docRefs }
                        .distinct()
                }.distinctUntilChanged()
                .collect { refs -> resolveDocTitles(refs) }
        }
    }

    /** Look up the display title of each not-yet-resolved [docRefs] entry (unknown ids stay absent). */
    private suspend fun resolveDocTitles(docRefs: List<String>) {
        docRefs
            .filterNot { it in _ui.value.docTitles }
            .forEach { docId ->
                runCatchingCancellable { documents.observeDoc(docId).first() }
                    .getOrNull()
                    ?.let { item ->
                        _ui.update { it.copy(docTitles = it.docTitles + (docId to item.doc.displayTitle)) }
                    }
            }
    }

    /** Send [question]; ignored when blank or while a request is in flight. */
    fun send(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _ui.value.busy) return
        val sessionId = _ui.value.currentSessionId ?: return

        _ui.update {
            it.copy(
                transcript = it.transcript.plusUser(trimmed),
                busy = true,
                sessions =
                    it.sessions.map { session ->
                        if (session.id == sessionId && session.title == NEW_SESSION_TITLE) {
                            session.copy(title = trimmed.replace(Regex("\\s+"), " ").take(MAX_SESSION_TITLE_CHARS))
                        } else {
                            session
                        }
                    },
            )
        }

        job =
            viewModelScope.launch {
                runCatchingCancellable { collectAnswer(sessionId, trimmed) }
                    .onFailure { e ->
                        finishStreaming()
                        _ui.update { it.copy(busy = false) }
                        message(uiText(UiTextKey.AI_ASSISTANT_UNREACHABLE, e.reasonText()))
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
        _ui.update {
            it.copy(
                transcript = ChatTranscript.EMPTY,
                busy = false,
                sessions =
                    it.sessions.map { session ->
                        if (session.id == it.currentSessionId) session.copy(title = NEW_SESSION_TITLE) else session
                    },
            )
        }
        _ui.value.currentSessionId?.let { sessionId ->
            launchReporting({ reason -> uiText(Res.string.ai_chat_clear_failed, reason) }) {
                ai.clearChatSession(sessionId)
            }
        }
    }

    /** Switch to an existing conversation and restore its visible turns. */
    fun switchSession(sessionId: String) {
        if (_ui.value.busy || _ui.value.sessions.none { it.id == sessionId }) return
        stop()
        viewModelScope.launch { loadSession(sessionId) }
    }

    /** Create and select a new empty conversation. */
    fun newSession() {
        if (_ui.value.busy) return
        launchReporting({ reason -> uiText(Res.string.ai_chat_new_failed, reason) }) {
            ai.createChatSession()?.let { session ->
                val savedSessions = runCatchingCancellable { ai.chatSessions() }.getOrDefault(_ui.value.sessions)
                _ui.update {
                    it.copy(
                        sessions = (listOf(session) + savedSessions).distinctBy { item -> item.id },
                        currentSessionId = session.id,
                        transcript = ChatTranscript.EMPTY,
                    )
                }
            }
        }
    }

    /** Delete a conversation and select the newest remaining one. */
    fun deleteSession(sessionId: String) {
        if (_ui.value.busy) return
        launchReporting({ reason -> uiText(Res.string.ai_chat_delete_failed, reason) }) {
            ai.deleteChatSession(sessionId)
            val remaining = runCatchingCancellable { ai.chatSessions() }.getOrDefault(emptyList())
            if (sessionId != _ui.value.currentSessionId) {
                _ui.update { it.copy(sessions = remaining) }
                return@launchReporting
            }
            val next = remaining.firstOrNull() ?: ai.createChatSession()
            if (next == null) {
                _ui.update {
                    it.copy(
                        sessions = emptyList(),
                        currentSessionId = null,
                        transcript = ChatTranscript.EMPTY,
                    )
                }
            } else {
                _ui.update { it.copy(sessions = (remaining + next).distinctBy { item -> item.id }) }
                loadSession(next.id)
            }
        }
    }

    private suspend fun collectAnswer(
        sessionId: String,
        question: String,
    ) {
        ai.ask(sessionId, question).collect { event ->
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

    private suspend fun loadSession(sessionId: String) {
        val history = runCatchingCancellable { ai.chatTranscriptFor(sessionId) }.getOrDefault(emptyList())
        _ui.update {
            it.copy(
                currentSessionId = sessionId,
                transcript = ChatTranscript.fromPersistedHistory(history),
                busy = false,
            )
        }
    }

    private companion object {
        const val NEW_SESSION_TITLE = "New chat"
        const val MAX_SESSION_TITLE_CHARS = 60
        const val LEGACY_SESSION_ID = "main"
    }
}
