package dev.npn.core.ai

import ai.koog.prompt.streaming.StreamFrame

/** A tool call that is safe to dispatch and replay into the next prompt. */
internal data class UsableToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

/** Drop malformed gateway frames instead of dispatching an empty tool name. */
internal fun StreamFrame.ToolCallComplete.toUsableToolCall(index: Int): UsableToolCall? {
    val toolName = name.trim().takeIf { it.isNotEmpty() } ?: return null
    return UsableToolCall(
        id = id?.takeUnless { it.isBlank() } ?: "call_$index",
        name = toolName,
        argsJson = content,
    )
}
