package dev.npn.core.ai

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import dev.npn.core.db.KeyValueEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.util.NpnJson
import kotlinx.serialization.builtins.ListSerializer

/** Device-local chat history stored in the library's disposable Room cache. */
class RoomChatHistoryProvider(
    private val db: NpnDatabase,
) : ChatHistoryProvider {
    private val json = NpnJson.Persisted

    override suspend fun store(
        conversationId: String,
        messages: List<Message>,
    ) {
        val cleanMessages = sanitize(messages)
        db.keyValueDao().put(
            KeyValueEntity(
                key = key(conversationId),
                value = json.encodeToString(ListSerializer(Message.serializer()), cleanMessages),
            ),
        )
    }

    override suspend fun load(conversationId: String): List<Message> =
        db.keyValueDao().get(key(conversationId))?.let {
            runCatching {
                json.decodeFromString(ListSerializer(Message.serializer()), it).let(::sanitize)
            }.getOrDefault(emptyList())
        } ?: emptyList()

    suspend fun delete(conversationId: String) {
        db.keyValueDao().delete(key(conversationId))
    }

    private fun key(conversationId: String): String = "ai-chat:$conversationId"

    /** Remove malformed blank-name tool parts already written by older agent runs. */
    private fun sanitize(messages: List<Message>): List<Message> =
        messages.mapNotNull { message ->
            when (message) {
                is Message.Assistant -> {
                    val parts =
                        message.parts.filterNot { part ->
                            part is MessagePart.Tool.Call && part.tool.isBlank()
                        }
                    message.copy(parts = parts).takeIf { parts.isNotEmpty() }
                }

                is Message.User -> {
                    val parts =
                        message.parts.filterNot { part ->
                            part is MessagePart.Tool.Result && part.tool.isBlank()
                        }
                    message.copy(parts = parts).takeIf { parts.isNotEmpty() }
                }

                else -> {
                    message
                }
            }
        }
}
