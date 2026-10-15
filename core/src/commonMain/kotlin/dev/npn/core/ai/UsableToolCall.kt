package dev.npn.core.ai

import ai.koog.prompt.streaming.StreamFrame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A tool call that is safe to dispatch and replay into the next prompt. */
internal data class UsableToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

/**
 * Drop malformed gateway frames instead of dispatching an empty tool name. A call without an id
 * gets a random one: the conversation is stored and replayed, so a fallback id must not repeat
 * anywhere in it, across iterations and turns alike.
 */
internal fun StreamFrame.ToolCallComplete.toUsableToolCall(): UsableToolCall? {
    val toolName = name.trim().takeIf { it.isNotEmpty() } ?: return null
    return UsableToolCall(
        id = id?.takeUnless { it.isBlank() } ?: fallbackCallId(),
        name = toolName,
        argsJson = content,
    )
}

@OptIn(ExperimentalUuidApi::class)
private fun fallbackCallId(): String = "call_" + Uuid.random().toHexString()
