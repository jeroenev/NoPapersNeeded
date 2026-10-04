package dev.npn.core.ai

import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.KeyValueEntity
import dev.npn.core.util.NpnJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * Device-local metadata for the conversations stored by [RoomChatHistoryProvider], in the same
 * [kv]. Writes go through [ownWrite], so the session can wait them out before it closes the
 * database.
 */
internal class RoomChatSessionStore(
    private val kv: KeyValueDao,
    private val ownWrite: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    private val json = NpnJson.Persisted

    suspend fun load(): List<AiChatSession> =
        kv
            .get(KEY)
            ?.let {
                runCatching { json.decodeFromString(ListSerializer(AiChatSession.serializer()), it) }
                    .getOrDefault(emptyList())
            }.orEmpty()
            .map { it.untitledIfPlaceholder() }

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
     * as untitled, so the UI names them in the user's language.
     */
    private fun AiChatSession.untitledIfPlaceholder(): AiChatSession =
        if (title in LEGACY_PLACEHOLDER_TITLES) copy(title = null) else this

    private companion object {
        const val KEY = "${RoomChatHistoryProvider.KEY_PREFIX}session-metadata"
        val LEGACY_PLACEHOLDER_TITLES = setOf("New chat", "Assistant chat")
    }
}
