package dev.npn.core.ai

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The conversation id to report to the endpoint for every request made inside this coroutine
 * context.
 *
 * The OpenCode Go gateway wants one stable `x-opencode-session` id per conversation, but Koog's
 * provider clients expose no per-request header hook: the header can only be stamped inside the
 * Ktor client, far below the call site that knows which conversation a request belongs to.
 * Carrying it in the coroutine context bridges that gap without threading a parameter through
 * every Koog API. Chat installs it around a turn (see `AiFacadeImpl.ask`, value = the chat
 * session id, so every turn of one conversation reuses one id); requests made without it fall
 * back to a per-client id (see [AiRuntime.openCodeGoClient]).
 */
internal class AiGatewaySession(
    val id: String,
) : AbstractCoroutineContextElement(AiGatewaySession) {
    companion object Key : CoroutineContext.Key<AiGatewaySession>
}

/** The [AiGatewaySession] id in scope, or null when the caller set none. */
internal suspend fun currentGatewaySessionId(): String? = coroutineContext[AiGatewaySession]?.id
