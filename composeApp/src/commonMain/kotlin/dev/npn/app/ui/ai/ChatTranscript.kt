package dev.npn.app.ui.ai

import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatTurn
import dev.npn.core.ai.AiRole
import dev.npn.core.ai.AiText

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
 * Immutable chat transcript plus the single definition of the fold rules that turn a stream of
 * [AiChatEvent]s into rendered [TranscriptItem]s. The reducer is pure: text deltas grow the
 * trailing streaming [TranscriptItem.AssistantTurn], tool events insert and complete
 * [TranscriptItem.ToolUse] items in order, and each finalised assistant bubble carries only the
 * docRefs it cited. No snackbar/message/effect logic lives here — callers observe
 * [hasVisibleContent] and drive UI feedback themselves.
 */
data class ChatTranscript(
    val items: List<TranscriptItem> = emptyList(),
) {
    /**
     * Fold one [event] into the transcript:
     *  - [AiChatEvent.TextDelta] grows the trailing streaming [TranscriptItem.AssistantTurn], or
     *    starts one when the trailing item is not a streaming assistant turn.
     *  - [AiChatEvent.ToolCallStarted] finalises the trailing streaming assistant turn then
     *    appends a running [TranscriptItem.ToolUse].
     *  - [AiChatEvent.ToolCallFinished] completes the last matching running [TranscriptItem.ToolUse]
     *    by name.
     *  - [AiChatEvent.Done] finalises the trailing assistant turn, or — when no delta ever arrived
     *    — materialises `fullText` as one finished turn.
     */
    operator fun plus(event: AiChatEvent): ChatTranscript =
        when (event) {
            is AiChatEvent.TextDelta -> appendDelta(event.text)
            is AiChatEvent.ToolCallStarted -> startTool(event.name, event.argsJson)
            is AiChatEvent.ToolCallFinished -> finishTool(event.name, event.result)
            is AiChatEvent.Done -> completeAssistant(event.fullText)
        }

    /** Append a [TranscriptItem.UserTurn] for [text]. */
    fun plusUser(text: String): ChatTranscript = ChatTranscript(items + TranscriptItem.UserTurn(text))

    private fun appendDelta(delta: String): ChatTranscript {
        val last = items.lastOrNull()
        return if (last is TranscriptItem.AssistantTurn && last.streaming) {
            ChatTranscript(items.dropLast(1) + last.copy(text = last.text + delta))
        } else {
            ChatTranscript(items + TranscriptItem.AssistantTurn(delta, emptyList(), streaming = true))
        }
    }

    private fun startTool(
        name: String,
        argsJson: String,
    ): ChatTranscript =
        // A tool call ends the current narration turn: finalise the trailing streaming assistant
        // turn (extracting its own docRefs) so pre-tool text is committed once and never merged
        // with post-tool text or overwritten by the run's full answer later.
        ChatTranscript(
            finaliseTrailingAssistant(items) +
                TranscriptItem.ToolUse(name, argsJson, result = "", running = true),
        )

    private fun finishTool(
        name: String,
        result: String,
    ): ChatTranscript {
        val index =
            items.indexOfLast {
                it is TranscriptItem.ToolUse && it.name == name && it.running
            }
        if (index < 0) return this
        val tool = items[index] as TranscriptItem.ToolUse
        val updated = items.toMutableList()
        updated[index] = tool.copy(result = result, running = false)
        return ChatTranscript(updated)
    }

    private fun completeAssistant(fullText: String): ChatTranscript {
        val last = items.lastOrNull()
        return if (last is TranscriptItem.AssistantTurn) {
            // Finalise ONLY the trailing turn using its own accumulated delta text; never
            // overwrite it with the run's full answer (which spans earlier, already-finalised
            // turns). docRefs are extracted from this turn's own text so each bubble links only
            // the documents it actually cited.
            ChatTranscript(finaliseTrailingAssistant(items))
        } else {
            // No delta ever arrived (e.g. an answer produced entirely through the non-streaming
            // fallback that only emits Done): materialise the full answer as one finished turn.
            ChatTranscript(
                items +
                    TranscriptItem.AssistantTurn(fullText, AiText.extractDocRefs(fullText), streaming = false),
            )
        }
    }

    /** Mark any trailing still-streaming assistant turn as complete (keeps partial text). */
    fun finalized(): ChatTranscript = ChatTranscript(finaliseTrailingAssistant(items))

    /** History for the next ask: user/assistant text turns only (tool items excluded). */
    val history: List<AiChatTurn>
        get() =
            items.mapNotNull { item ->
                when (item) {
                    is TranscriptItem.UserTurn -> AiChatTurn(AiRole.USER, item.text)
                    is TranscriptItem.AssistantTurn -> AiChatTurn(AiRole.ASSISTANT, item.text)
                    is TranscriptItem.ToolUse -> null
                }
            }

    /** True when the transcript shows any tool row or any non-blank assistant text. */
    val hasVisibleContent: Boolean
        get() =
            items.any { item ->
                when (item) {
                    is TranscriptItem.ToolUse -> true
                    is TranscriptItem.AssistantTurn -> item.text.isNotBlank()
                    is TranscriptItem.UserTurn -> false
                }
            }

    companion object {
        val EMPTY = ChatTranscript()

        /**
         * If the trailing item is a still-streaming [TranscriptItem.AssistantTurn], mark it complete
         * (keeping its own accumulated text) and extract its docRefs; otherwise leave the list
         * as-is.
         */
        private fun finaliseTrailingAssistant(items: List<TranscriptItem>): List<TranscriptItem> {
            val last = items.lastOrNull()
            return if (last is TranscriptItem.AssistantTurn && last.streaming) {
                items.dropLast(1) + last.copy(docRefs = AiText.extractDocRefs(last.text), streaming = false)
            } else {
                items
            }
        }
    }
}
