package dev.npn.core.ai

import ai.koog.agents.core.tools.ToolDescriptor

/**
 * One tool the streaming chat loop can advertise and invoke. [descriptor] is handed to the
 * model on every request so it knows the tool's schema; [call] decodes the raw arguments JSON
 * the model streamed back and runs the tool, returning its (already bounded) text result.
 * Kept as a small interface so [AiChatLoop] is testable with fakes and no database.
 */
interface AiChatTool {
    val name: String
    val descriptor: ToolDescriptor
    suspend fun call(argsJson: String): String
}
