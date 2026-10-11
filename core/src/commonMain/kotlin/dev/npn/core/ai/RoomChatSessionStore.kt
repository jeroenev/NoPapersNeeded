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
                // The next edit reads this list before writing it. A decode failure must stop
                // that edit rather than replace every saved session with a new empty list.
                json.decodeFromString(ListSerializer(AiChatSession.serializer()), it)
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
     *
     * A session asked "New chat" is titled exactly like a placeholder, and keeps its title: only a
     * title its own first question could not have produced is one.
     */
    private suspend fun AiChatSession.fromPlaceholder(): AiChatSession? {
        if (title !in LEGACY_PLACEHOLDER_TITLES) return this
        val firstQuestion =
            KoogChatHistoryMapper
                .map(conversations.load(id))
                .filterIsInstance<AiChatHistoryItem.UserTurn>()
                .firstOrNull()
        if (firstQuestion != null && AiChatSession.titleFrom(firstQuestion.text) == title) return this
        return if (conversations.has(id)) copy(title = null) else null
    }

    private companion object {
        const val KEY = "${RoomChatHistoryProvider.KEY_PREFIX}session-metadata"
        val LEGACY_PLACEHOLDER_TITLES = setOf("New chat", "Assistant chat")
    }
}
