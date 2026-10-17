package dev.npn.core.ai

import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.KeyValueEntity
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.util.NpnJson
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The assistant's chat sessions: the device-local list of them, kept in [kv] beside their
 * [conversations]. Writes go through [ownWrite], so the session can wait them out before it closes
 * the database; every change to the list reads, changes and stores it as one step.
 */
internal class ChatSessions(
    private val kv: KeyValueDao,
    private val ownWrite: suspend (suspend () -> Unit) -> Unit = { it() },
    private val conversations: RoomChatHistoryProvider = RoomChatHistoryProvider(kv),
    private val clock: EpochClock = SystemEpochClock,
) {
    private val json = NpnJson.Persisted

    /** Serializes the read-modify-write of the session list across ask, create, clear and delete. */
    private val mutex = Mutex()

    /** Every session, newest first; see [AiChat.chatSessions]. */
    suspend fun list(): List<AiChatSession> {
        // Every stored session is listed: one is stored once asked in, and stays (untitled once
        // cleared) until it is deleted.
        val stored = load()
        // The one conversation of a build from before sessions existed, listed untitled beside the
        // others while it has history, until it is deleted or asked in (ask stores it then).
        val legacy =
            if (stored.none { it.id == LEGACY_CONVERSATION_ID } && conversations.has(LEGACY_CONVERSATION_ID)) {
                listOf(AiChatSession(LEGACY_CONVERSATION_ID, title = null, updatedAt = 0L))
            } else {
                emptyList()
            }
        return (stored + legacy).sortedByDescending { it.updatedAt }
    }

    /** A new, untitled session; stored only once it is asked in ([touch]). */
    @OptIn(ExperimentalUuidApi::class)
    fun create(): AiChatSession = AiChatSession(Uuid.random().toString(), null, now())

    suspend fun delete(sessionId: String) {
        conversations.delete(sessionId)
        edit { sessions -> sessions.filterNot { it.id == sessionId } }
    }

    suspend fun clear(sessionId: String) {
        // Stored untitled, so it stays listed; that includes the pre-sessions conversation, which
        // is listed by its history alone until now.
        val hadConversation = conversations.has(sessionId)
        conversations.delete(sessionId)
        edit { sessions ->
            if (!hadConversation && sessions.none { it.id == sessionId }) {
                sessions
            } else {
                sessions.filterNot { it.id == sessionId } + AiChatSession(sessionId, title = null, updatedAt = now())
            }
        }
    }

    /** Store [sessionId] as just used, titled by its first [question] while it has no title yet. */
    suspend fun touch(
        sessionId: String,
        question: String,
    ) = edit { sessions ->
        val title = sessions.firstOrNull { it.id == sessionId }?.title ?: AiChatSession.titleFrom(question)
        sessions.filterNot { it.id == sessionId } + AiChatSession(sessionId, title, now())
    }

    /** Read, change and store the session list as one step, newest first. */
    private suspend fun edit(change: (List<AiChatSession>) -> List<AiChatSession>) =
        mutex.withLock { save(change(load()).sortedByDescending { it.updatedAt }) }

    private fun now(): Long = clock.nowMillis()

    /** The stored list, as it reads now; a test seeds and reads it directly. */
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
     * title its own first question could not have produced is one. A conversation that cannot be
     * decoded keeps its session listed untitled: its own reads refuse it, and it must not stop the
     * list or edits to every other session.
     */
    private suspend fun AiChatSession.fromPlaceholder(): AiChatSession? {
        if (title !in LEGACY_PLACEHOLDER_TITLES) return this
        if (!conversations.has(id)) return null
        val firstQuestion =
            runCatchingCancellable {
                KoogChatHistoryMapper
                    .map(conversations.load(id))
                    .filterIsInstance<AiChatHistoryItem.UserTurn>()
                    .firstOrNull()
            }.getOrNull()
        if (firstQuestion != null && AiChatSession.titleFrom(firstQuestion.text) == title) return this
        return copy(title = null)
    }

    private companion object {
        const val KEY = "${RoomChatHistoryProvider.KEY_PREFIX}session-metadata"
        val LEGACY_PLACEHOLDER_TITLES = setOf("New chat", "Assistant chat")

        /** The conversation id builds from before sessions existed stored their one chat under. */
        const val LEGACY_CONVERSATION_ID = "main"
    }
}
