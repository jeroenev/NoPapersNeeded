package dev.npn.core.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/** Converts Koog's persisted message parts into the items rendered by the chat transcript. */
internal object KoogChatHistoryMapper {
    fun map(messages: List<Message>): List<AiChatHistoryItem> {
        val items = mutableListOf<AiChatHistoryItem>()
        val toolIndexes = mutableMapOf<String, Int>()

        messages.forEach { message ->
            when (message) {
                is Message.User -> {
                    val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
                    if (text.isNotBlank()) items += AiChatHistoryItem.UserTurn(text)
                    message.parts.filterIsInstance<MessagePart.Tool.Result>().forEach { result ->
                        val index = toolIndexes[result.id] ?: return@forEach
                        val tool = items[index] as AiChatHistoryItem.ToolUse
                        items[index] = tool.copy(result = result.output ?: "")
                    }
                }

                is Message.Assistant -> {
                    message.textContent().takeIf { it.isNotBlank() }?.let { text ->
                        items += AiChatHistoryItem.AssistantTurn(text)
                    }
                    message.parts.filterIsInstance<MessagePart.Tool.Call>().forEach { call ->
                        toolIndexes[call.id ?: "tool-${items.size}"] = items.size
                        items += AiChatHistoryItem.ToolUse(call.tool, call.args ?: "{}", result = "")
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        return items
    }
}
