package dev.npn.core.ai

import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.KeyValueEntity
import dev.npn.core.util.NpnJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * Device-local metadata for the [conversations] stored in the same [kv]. Writes go through
 * [ownWrite], so the session can wait them out before it closes the database.
 */
internal class RoomChatSessionStore(
    private val kv: KeyValueDao,
    private val ownWrite: suspend (suspend () -> Unit) -> Unit = { it() },
    private val conversations: RoomChatHistoryProvider = RoomChatHistoryProvider(kv),
) {
    private val json = NpnJson.Persisted

    suspend fun load(): List<AiChatSession> =
        kv
            .get(KEY)
            ?.let {
                runCatching { json.decodeFromString(ListSerializer(AiChatSession.serializer()), it) }
                    .getOrDefault(emptyList())
            }.orEmpty()
            .mapNotNull { it.fromPlaceholder() }

    suspend fun save(sessions: List<AiChatSession>) =
        ownWrite {
            kv.put(
                KeyValueEntity(
                    key = KEY,
                    value = json.encodeToString(ListSerializer(AiChatSession.serializer()), sessions),
                ),
            )
        }

    /**
     * Builds before nullable titles stored English placeholders in place of "untitled"; they read
     * as untitled, so the UI names them in the user's language. Those builds also stored a session
     * as soon as it was created, so a placeholder without a conversation was never asked in, and
     * reads as absent.
     */
    private suspend fun AiChatSession.fromPlaceholder(): AiChatSession? =
        when {
            title !in LEGACY_PLACEHOLDER_TITLES -> this
            conversations.has(id) -> copy(title = null)
            else -> null
        }

    private companion object {
        const val KEY = "${RoomChatHistoryProvider.KEY_PREFIX}session-metadata"
        val LEGACY_PLACEHOLDER_TITLES = setOf("New chat", "Assistant chat")
    }
}
