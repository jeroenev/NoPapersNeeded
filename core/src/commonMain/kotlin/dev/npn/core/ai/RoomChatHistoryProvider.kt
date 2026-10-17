package dev.npn.core.ai

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.KeyValueEntity
import dev.npn.core.util.NpnJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * Device-local chat history, kept in [kv]: the library's device database, which an index rebuild
 * leaves alone, since a conversation is user content nothing could recreate. Writes go through
 * [ownWrite], so the session can wait them out before it closes the database.
 */
internal class RoomChatHistoryProvider(
    private val kv: KeyValueDao,
    private val ownWrite: suspend (suspend () -> Unit) -> Unit = { it() },
) : ChatHistoryProvider {
    private val json = NpnJson.Persisted

    override suspend fun store(
        conversationId: String,
        messages: List<Message>,
    ) {
        val cleanMessages = sanitize(messages)
        ownWrite {
            kv.put(
                KeyValueEntity(
                    key = key(conversationId),
                    value = json.encodeToString(ListSerializer(Message.serializer()), cleanMessages),
                ),
            )
        }
    }

    override suspend fun load(conversationId: String): List<Message> =
        kv
            .get(key(conversationId))
            ?.let {
                // Undecodable history is still user content. Reading it as empty would let the
                // next turn replace it, including history written by a newer Koog version.
                json.decodeFromString(ListSerializer(Message.serializer()), it).let(::sanitize)
            }.orEmpty()

    /** Whether [conversationId] has anything stored, without decoding it. */
    suspend fun has(conversationId: String): Boolean = kv.get(key(conversationId)) != null

    suspend fun delete(conversationId: String) = ownWrite { kv.delete(key(conversationId)) }

    private fun key(conversationId: String): String = "$KEY_PREFIX$conversationId"

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

    companion object {
        /** Every chat key starts with this, the session list's included ([ChatSessions]). */
        const val KEY_PREFIX = "ai-chat:"
    }
}
