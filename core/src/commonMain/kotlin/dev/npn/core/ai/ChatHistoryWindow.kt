package dev.npn.core.ai

import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * How much of a chat conversation is kept, and how much of it one request replays. Measured in
 * characters, not messages: a tool result can run to [LibraryAiTools.TEXT_CHUNK_CHARS] and a
 * question to twenty, so a message count says nothing about what a request costs.
 *
 * A conversation is cut only between turns (a question and everything done to answer it), so a
 * window never opens on a tool result whose call was cut off, which strict endpoints reject. System
 * messages are always kept, and so is the newest turn, however long: it is the one being answered.
 */
internal object ChatHistoryWindow {
    /** What a conversation keeps for its transcript and later turns, tool results in full. */
    const val MEMORY_CHARS = 200_000

    /** What one request replays, the turn being answered included. */
    const val REQUEST_CHARS = 32_000

    /**
     * Turns, counting the one being answered, whose tool results a request replays in full. Older
     * results are stubbed: the answers that used them are still there, and the model can call the
     * tool again.
     */
    const val FULL_TOOL_RESULT_TURNS = 2

    /** Below this a tool result is no bigger than its stub, so it is replayed as it is. */
    private const val STUB_MAX_CHARS = 120

    /**
     * The chat memory's load and store step: what the conversation keeps, the newest turns that fit
     * in [MEMORY_CHARS] with every tool result in full, so a reopened transcript still shows them.
     */
    val memory: ChatMemoryPreProcessor =
        object : ChatMemoryPreProcessor {
            override fun preprocess(messages: List<Message>): List<Message> =
                window(messages, MEMORY_CHARS, fullToolResultTurns = Int.MAX_VALUE)
        }

    /** What one request sends of the conversation (see [FULL_TOOL_RESULT_TURNS], [REQUEST_CHARS]). */
    fun forRequest(messages: List<Message>): List<Message> = window(messages, REQUEST_CHARS, FULL_TOOL_RESULT_TURNS)

    /**
     * The system messages of [messages], then its newest turns, newest first, until the next would
     * take the total past [maxChars]. The tool results of all but the last [fullToolResultTurns]
     * turns are stubbed before they are counted.
     */
    fun window(
        messages: List<Message>,
        maxChars: Int,
        fullToolResultTurns: Int,
    ): List<Message> {
        val kept = ArrayDeque<List<Message>>()
        var chars = 0
        for ((age, turn) in turns(messages).asReversed().withIndex()) {
            val replayed = if (age < fullToolResultTurns) turn else turn.map(::stubToolResults)
            chars += replayed.sumOf(::charsOf)
            if (age > 0 && chars > maxChars) break
            kept.addFirst(replayed)
        }
        return messages.filterIsInstance<Message.System>() + kept.flatten()
    }

    /**
     * The non-system messages of [messages] split before each question. Whatever precedes the first
     * question is the tail of a turn an older window cut, and is dropped.
     */
    private fun turns(messages: List<Message>): List<List<Message>> {
        val turns = mutableListOf<MutableList<Message>>()
        for (message in messages) {
            when {
                message is Message.System -> Unit
                message.isQuestion() -> turns += mutableListOf(message)
                else -> turns.lastOrNull()?.add(message)
            }
        }
        return turns
    }

    private fun Message.isQuestion(): Boolean = this is Message.User && parts.none { it is MessagePart.Tool.Result }

    private fun stubToolResults(message: Message): Message {
        if (message !is Message.User) return message
        val parts =
            message.parts.map { part ->
                if (part is MessagePart.Tool.Result && part.output.length > STUB_MAX_CHARS) {
                    // Same id and tool, so the call it answers still pairs up with it.
                    MessagePart.Tool.Result(part.id, part.tool, stubFor(part.tool), part.isError, part.cacheControl)
                } else {
                    part
                }
            }
        return message.copy(parts = parts)
    }

    private fun stubFor(tool: String): String = "[Earlier $tool result omitted. Call $tool again if you need it.]"

    private fun charsOf(message: Message): Int =
        message.parts.sumOf { part ->
            when (part) {
                is MessagePart.Text -> part.text.length
                is MessagePart.Tool.Call -> part.tool.length + part.args.length
                is MessagePart.Tool.Result -> part.output.length
                is MessagePart.Reasoning -> part.content.sumOf { it.length } + (part.encrypted?.length ?: 0)
                else -> 0
            }
        }
}
