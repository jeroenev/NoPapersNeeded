package dev.npn.core.ai

import dev.npn.core.db.KeyValueEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.util.NpnJson
import kotlinx.serialization.builtins.ListSerializer

/** Device-local metadata for the conversations stored by [RoomChatHistoryProvider]. */
class RoomChatSessionStore(
    private val db: NpnDatabase,
) {
    private val json = NpnJson.Persisted

    suspend fun load(): List<AiChatSession> =
        db.keyValueDao().get(KEY)?.let {
            runCatching { json.decodeFromString(ListSerializer(AiChatSession.serializer()), it) }
                .getOrDefault(emptyList())
        } ?: emptyList()

    suspend fun save(sessions: List<AiChatSession>) {
        db.keyValueDao().put(
            KeyValueEntity(
                key = KEY,
                value = json.encodeToString(ListSerializer(AiChatSession.serializer()), sessions),
            ),
        )
    }

    private companion object {
        const val KEY = "ai-chat:session-metadata"
    }
}
