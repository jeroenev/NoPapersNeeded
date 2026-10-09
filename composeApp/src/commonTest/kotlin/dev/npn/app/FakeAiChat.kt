package dev.npn.app

import dev.npn.core.ai.AiChat
import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatHistoryItem
import dev.npn.core.ai.AiChatSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Chat fake: [askScripts] are event lists consumed in order (last one repeats); each backs one
 * [ask] flow. [askResponses] is a convenience: a plain answer string is turned into a `TextDelta`
 * + `Done` script. Failure injection via [failAsk] (throws when the returned flow is collected)
 * and [failAskAfter] (throws mid-stream), call recording via [asked]. [askDelayMillis] delays each
 * emitted event so tests can observe intermediate streaming state and cancellation.
 */
class FakeAiChat : AiChat {
    var configuredValue: Boolean = true

    var askScripts: List<List<AiChatEvent>> = emptyList()
    var askResponses: List<String> = emptyList()
    var askDelayMillis: Long = 0
    private var askIdx = 0

    /** (sessionId, question) pairs passed to [ask], in order. */
    val asked = mutableListOf<Pair<String, String>>()
    var failAsk: (() -> Nothing)? = null

    /** When set, the ask flow throws after emitting this many events (mid-stream failure). */
    var failAskAfter: Int? = null

    var savedChatSessions: List<AiChatSession> = emptyList()
    var createdChatSession: AiChatSession = AiChatSession("created", null, 0L)
    var chatTranscripts: Map<String, List<AiChatHistoryItem>> = emptyMap()

    override suspend fun configured(): Boolean = configuredValue

    override suspend fun chatSessions(): List<AiChatSession> = savedChatSessions

    override suspend fun createChatSession(): AiChatSession = createdChatSession

    /** If set, [deleteChatSession] throws this. */
    var failDeleteChatSession: (() -> Nothing)? = null

    override suspend fun deleteChatSession(sessionId: String) {
        failDeleteChatSession?.invoke()
        savedChatSessions = savedChatSessions.filterNot { it.id == sessionId }
    }

    override suspend fun clearChatSession(sessionId: String) = Unit

    override suspend fun chatTranscriptFor(sessionId: String): List<AiChatHistoryItem> =
        chatTranscripts[sessionId].orEmpty()

    override fun ask(
        sessionId: String,
        question: String,
    ): Flow<AiChatEvent> {
        val script = scriptFor(askIdx)
        askIdx++
        asked += sessionId to question
        return flow {
            failAsk?.invoke()
            var emitted = 0
            for (event in script) {
                if (failAskAfter == emitted) error("stream interrupted")
                if (askDelayMillis > 0) kotlinx.coroutines.delay(askDelayMillis)
                emit(event)
                emitted++
            }
        }
    }

    private fun scriptFor(index: Int): List<AiChatEvent> {
        if (askScripts.isNotEmpty()) return askScripts.getOrElse(index) { askScripts.last() }
        val text = askResponses.getOrElse(index) { askResponses.lastOrNull() ?: "answer" }
        return listOf(AiChatEvent.TextDelta(text), AiChatEvent.Done(text))
    }
}
