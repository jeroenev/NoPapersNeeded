package dev.npn.app.ui.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiState
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatHistoryItem
import dev.npn.core.ai.AiChatSession
import dev.npn.core.app.LibrarySession
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_chat_clear_failed
import no_papers_needed.composeapp.generated.resources.ai_chat_delete_failed
import no_papers_needed.composeapp.generated.resources.ai_chat_new_failed
import no_papers_needed.composeapp.generated.resources.ai_chat_open_failed
import no_papers_needed.composeapp.generated.resources.common_ai_assistant_empty_answer
import no_papers_needed.composeapp.generated.resources.common_ai_assistant_unreachable
import org.jetbrains.compose.resources.StringResource

/**
 * Chat UI state. [busy] is true while a request is streaming. [docTitles] resolves the documents the
 * transcript names (cited docRefs, documents a tool read) to display titles as they appear; an
 * unresolved ref falls back to a shortened id at the render site. [fieldLabels] names the fields a
 * tool row searched by their label rather than their key.
 */
data class AiChatUiState(
    val transcript: ChatTranscript = ChatTranscript.EMPTY,
    val sessions: List<AiChatSession> = emptyList(),
    val currentSessionId: String? = null,
    val busy: Boolean = false,
    /** A session is being created, selected or deleted; sending waits until its history is ready. */
    val loading: Boolean = true,
    val docTitles: Map<String, String> = emptyMap(),
    val fieldLabels: Map<String, String> = emptyMap(),
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
    session: LibrarySession,
    /** What the screen's back-stack entry saves for a restored process; a new one opens it afresh. */
    private val saved: SavedStateHandle = SavedStateHandle(),
) : MessagesViewModel() {
    private val ai = session.aiChat
    private val documents = session.documents
    override val log = session.log

    private val _ui = MutableStateFlow(AiChatUiState())
    val ui: StateFlow<AiChatUiState> = _ui.asStateFlow()

    /**
     * Change the UI state, saving which conversation is open: a restored process opens that one
     * again rather than a new one, as long as the store has it (it does once asked in).
     */
    private fun updateUi(transform: (AiChatUiState) -> AiChatUiState) {
        val open = _ui.updateAndGet(transform).currentSessionId
        if (open != saved.get<String>(SESSION_KEY)) saved[SESSION_KEY] = open
    }

    /** The question typed and not sent yet. */
    private val draftState = saved.uiState(DRAFT_KEY, "")
    val draft: StateFlow<String> = draftState.flow

    fun type(text: String) = draftState.set(text)

    private var job: Job? = null

    /** The latest [clear]'s deletion of the stored history, which a question sent after it waits for. */
    private var clearing: Job? = null

    init {
        viewModelScope.launch {
            try {
                reporting(Res.string.ai_chat_new_failed) {
                    val stored = storedSessions()
                    // The conversation a restored process had open, if the store has it; else a new one.
                    val reopened = saved.get<String>(SESSION_KEY)?.let { id -> stored.firstOrNull { it.id == id } }
                    val current = reopened ?: ai.createChatSession()
                    updateUi { it.copy(sessions = listed(stored, current), currentSessionId = current.id) }
                    loadSession(current.id)
                }
            } finally {
                updateUi { it.copy(loading = false) }
            }
        }
        // Resolve display titles for cited documents as they appear in the transcript, so the
        // bubbles can label their doc chips. Title lookup is data access and belongs here, not in
        // a leaf composable (where it would also re-fire as rows scroll in and out). Only a changed
        // transcript can cite anything new, and a streaming answer cites nothing until it completes,
        // so its tokens do not rescan the transcript.
        viewModelScope.launch {
            _ui
                .map { it.transcript }
                .distinctUntilChanged()
                .filterNot { it.streaming }
                .map { it.docRefs() }
                .distinctUntilChanged()
                .collect { refs -> resolveDocTitles(refs) }
        }
        // A tool row names the field it searched; without the registry it shows the key instead.
        viewModelScope.launch {
            log.runLogged("Reading the field labels for the chat") {
                session.fieldRegistry.fields.collect { defs ->
                    updateUi { it.copy(fieldLabels = defs.associate { def -> def.key to def.label }) }
                }
            }
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
                        updateUi { it.copy(docTitles = it.docTitles + (docId to item.doc.displayTitle)) }
                    }
            }
    }

    /**
     * Send [question]; ignored when blank or while a request is in flight. Sent right after [clear],
     * it is asked only once the clear has deleted the stored history: a turn loads that history and
     * stores it back with its answer, so one asked earlier would bring the cleared conversation back.
     */
    fun send(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _ui.value.busy || _ui.value.loading) return
        val sessionId = _ui.value.currentSessionId ?: return

        updateUi {
            it.copy(
                transcript = it.transcript.plusUser(trimmed),
                busy = true,
                // The title core gives the session from this question, shown before ask stores it.
                sessions =
                    it.sessions.map { session ->
                        if (session.id == sessionId && session.title == null) {
                            session.copy(title = AiChatSession.titleFrom(trimmed))
                        } else {
                            session
                        }
                    },
            )
        }

        val cleared = clearing
        job =
            viewModelScope.launch {
                cleared?.join()
                runCatchingCancellable { collectAnswer(sessionId, trimmed) }
                    .onFailure { e ->
                        finishStreaming()
                        updateUi { it.copy(busy = false) }
                        message(uiText(Res.string.common_ai_assistant_unreachable, e.reasonText()))
                    }
            }
    }

    /** Stop a streaming request: finalise the partial assistant turn (kept in history). */
    fun stop() {
        job?.cancel()
        job = null
        finishStreaming()
        updateUi { it.copy(busy = false) }
    }

    /**
     * Reset the conversation to an empty transcript and delete its stored history in the background.
     * Clears run one after another, so the latest one finishing means every one has.
     */
    fun clear() {
        if (_ui.value.loading) return
        job?.cancel()
        job = null
        updateUi {
            it.copy(
                transcript = it.transcript.cleared(),
                busy = false,
                sessions =
                    it.sessions.map { session ->
                        if (session.id == it.currentSessionId) session.copy(title = null) else session
                    },
            )
        }
        _ui.value.currentSessionId?.let { sessionId ->
            val previous = clearing
            clearing =
                viewModelScope.launch {
                    previous?.join()
                    reporting(Res.string.ai_chat_clear_failed) { ai.clearChatSession(sessionId) }
                }
        }
    }

    /** Switch to an existing conversation and restore its visible turns; one whose turns cannot be
     * read is named as such, and the conversation on screen stays.
     */
    fun switchSession(sessionId: String) {
        if (_ui.value.sessions.none { it.id == sessionId }) return
        launchTransition(Res.string.ai_chat_open_failed) { loadSession(sessionId) }
    }

    /** Create and select a new empty conversation. */
    fun newSession() =
        launchTransition(Res.string.ai_chat_new_failed) {
            val session = ai.createChatSession()
            val stored = storedSessions(ifUnreadable = _ui.value.sessions)
            updateUi {
                it.copy(
                    sessions = listed(stored, session),
                    currentSessionId = session.id,
                    transcript = it.transcript.cleared(),
                )
            }
        }

    /** Delete a conversation and select the newest remaining one. */
    fun deleteSession(sessionId: String) =
        launchTransition(Res.string.ai_chat_delete_failed) {
            ai.deleteChatSession(sessionId)
            val state = _ui.value
            val stored = storedSessions(ifUnreadable = state.sessions.filterNot { it.id == sessionId })
            if (sessionId != state.currentSessionId) {
                val current = state.sessions.firstOrNull { it.id == state.currentSessionId }
                updateUi { it.copy(sessions = listed(stored, current)) }
                return@launchTransition
            }
            val next = stored.firstOrNull() ?: ai.createChatSession()
            updateUi { it.copy(sessions = listed(stored, next)) }
            // The deleted conversation must not stay on screen when the next one's turns cannot be
            // read: that one shows empty and says so, rather than this transition reporting its
            // delete, which landed.
            runCatchingCancellable { loadSession(next.id) }.getOrElse { failure ->
                showSession(next.id, emptyList())
                message(uiText(Res.string.ai_chat_open_failed, failure.reasonText()))
            }
        }

    /** Claim the transition before launching: another action cannot start during its first read. */
    private fun launchTransition(
        failed: StringResource,
        block: suspend () -> Unit,
    ) {
        if (_ui.value.busy || _ui.value.loading) return
        updateUi { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                clearing?.join()
                reporting(failed, block)
            } finally {
                updateUi { it.copy(loading = false) }
            }
        }
    }

    /** The sessions the store lists, or [ifUnreadable] when it cannot be read. */
    private suspend fun storedSessions(ifUnreadable: List<AiChatSession> = emptyList()): List<AiChatSession> =
        runCatchingCancellable { ai.chatSessions() }
            .onFailure { message(uiText(Res.string.ai_chat_open_failed, it.reasonText())) }
            .getOrDefault(ifUnreadable)

    /**
     * The session list: [stored], with [current] in front while the store doesn't have it. A session
     * is stored only once asked in, and the open one must stay listed until then.
     */
    private fun listed(
        stored: List<AiChatSession>,
        current: AiChatSession?,
    ): List<AiChatSession> =
        if (current == null || stored.any { it.id == current.id }) stored else listOf(current) + stored

    private suspend fun collectAnswer(
        sessionId: String,
        question: String,
    ) {
        ai.ask(sessionId, question).collect { event ->
            updateUi { it.copy(transcript = it.transcript + event) }
            if (event is AiChatEvent.Done) {
                // A run that ends blank with nothing visible (no text bubble, no tool rows) would
                // leave no trace at all — the finished empty bubble is skipped by the screen — so
                // surface it. The check runs against the post-Done transcript.
                if (event.fullText.isBlank() && !_ui.value.transcript.hasVisibleContent) {
                    message(uiText(Res.string.common_ai_assistant_empty_answer))
                }
            }
        }
        updateUi { it.copy(busy = false) }
    }

    /** Mark any trailing still-streaming assistant turn as complete (keeps partial text). */
    private fun finishStreaming() = updateUi { it.copy(transcript = it.transcript.finalized()) }

    /** Show [sessionId]'s turns; a transcript that cannot be read throws, leaving the state as it is. */
    private suspend fun loadSession(sessionId: String) = showSession(sessionId, ai.chatTranscriptFor(sessionId))

    private fun showSession(
        sessionId: String,
        history: List<AiChatHistoryItem>,
    ) {
        updateUi {
            it.copy(
                currentSessionId = sessionId,
                transcript = ChatTranscript.fromPersistedHistory(history),
                busy = false,
            )
        }
    }

    private companion object {
        const val SESSION_KEY = "chat.session"
        const val DRAFT_KEY = "chat.draft"
    }
}
