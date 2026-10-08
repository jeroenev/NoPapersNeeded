package dev.npn.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** One rendered item restored from a persisted assistant conversation. */
sealed interface AiChatHistoryItem {
    data class UserTurn(
        val text: String,
    ) : AiChatHistoryItem

    data class AssistantTurn(
        val text: String,
    ) : AiChatHistoryItem

    data class ToolUse(
        val name: String,
        val argsJson: String,
        val result: String,
    ) : AiChatHistoryItem
}

/**
 * Metadata for one persisted assistant conversation. [title] is null while the conversation is
 * untitled (nothing asked yet, or cleared): the UI names that state in the user's language.
 */
@Serializable
data class AiChatSession(
    val id: String,
    val title: String?,
    val updatedAt: Long,
) {
    companion object {
        private const val MAX_TITLE_CHARS = 60
        private val whitespace = Regex("\\s+")

        /** The title a conversation takes from its first question, or null for a blank one. */
        fun titleFrom(question: String): String? =
            question
                .replace(whitespace, " ")
                .trim()
                .take(MAX_TITLE_CHARS)
                .ifBlank { null }
    }
}

/**
 * Streaming event from [AiChat.ask]. The cold flow emits any number of [TextDelta] and
 * tool events, then exactly one terminal [Done]. Network/endpoint errors propagate as flow
 * exceptions (the caller maps them to one-shot messages); the flow is cancellable at any point.
 */
sealed interface AiChatEvent {
    /** A chunk of assistant answer text to append to the current answer. */
    data class TextDelta(
        val text: String,
    ) : AiChatEvent

    /** The agent decided to call [name] with the raw arguments JSON [argsJson]. */
    data class ToolCallStarted(
        val name: String,
        val argsJson: String,
    ) : AiChatEvent

    /** The tool [name] finished; [result] is its (already bounded) text output. */
    data class ToolCallFinished(
        val name: String,
        val argsJson: String,
        val result: String,
    ) : AiChatEvent

    /** Terminal event carrying the full accumulated answer text. */
    data class Done(
        val fullText: String,
    ) : AiChatEvent
}

/**
 * The assistant conversation for one opened library: persisted sessions and the streaming ask.
 * All model calls go to the user-configured endpoint; nothing here writes to the journal.
 * Callers surface failures as UI messages ([ask] throws on network/endpoint errors).
 */
interface AiChat : AiAvailability {
    /**
     * Answer [question] within the persisted session [sessionId] with a tool-calling agent that
     * can search the library index (OCR text, tags, filenames, metadata). Conversation history is
     * whatever the session already persisted — the caller never passes it. Returns a cold [Flow]
     * of [AiChatEvent]s: answer text streams in as [AiChatEvent.TextDelta]s, tool use surfaces as
     * [AiChatEvent.ToolCallStarted]/[AiChatEvent.ToolCallFinished], and the run ends with one
     * [AiChatEvent.Done]. The answer text may reference documents as `[doc:<docId>]`. Collection
     * starts the request; cancelling the collection stops it. Network/endpoint errors are thrown
     * from the flow.
     */
    fun ask(
        sessionId: String,
        question: String,
    ): Flow<AiChatEvent>

    /** Persisted transcript items for a session, including completed tool uses. */
    suspend fun chatTranscriptFor(sessionId: String): List<AiChatHistoryItem>

    /**
     * Every session asked in and not deleted since, a cleared one untitled, newest first. A read:
     * it never writes, and never decodes a conversation to answer.
     */
    suspend fun chatSessions(): List<AiChatSession>

    /**
     * A new untitled session. It is stored once something is asked in it, so a session nobody
     * used leaves nothing behind.
     */
    suspend fun createChatSession(): AiChatSession

    /** Delete a session and its stored conversation. */
    suspend fun deleteChatSession(sessionId: String)

    /**
     * Delete a session's messages. It stays in the session list, untitled, until it is deleted;
     * its next question titles it again.
     */
    suspend fun clearChatSession(sessionId: String)
}

private val docRefRegex = Regex("\\[doc:([0-9a-fA-F]{64})]")

/** Full docIds an assistant answer cites as `[doc:<hash>]`, in first-occurrence order. */
fun extractDocRefs(text: String): List<String> =
    docRefRegex
        .findAll(text)
        .map { it.groupValues[1].lowercase() }
        .distinct()
        .toList()
