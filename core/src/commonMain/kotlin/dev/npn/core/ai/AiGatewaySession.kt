package dev.npn.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestPipeline
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The OpenCode Go gateway's conversation id: what a request reports ([AiGatewaySession]) and the
// client that stamps it on the wire ([withOpenCodeSession]).

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
 * back to a per-client id (see [withOpenCodeSession]).
 */
internal class AiGatewaySession(
    val id: String,
) : AbstractCoroutineContextElement(AiGatewaySession) {
    companion object Key : CoroutineContext.Key<AiGatewaySession>
}

/** The [AiGatewaySession] id in scope, or null when the caller set none. */
private suspend fun currentGatewaySessionId(): String? = coroutineContext[AiGatewaySession]?.id

private const val OPENCODE_SESSION_HEADER = "x-opencode-session"

/**
 * The OpenCode Go gateway rejects requests that carry no `x-opencode-session` header (one stable
 * id per conversation), so a client bound for it derives a copy that stamps one
 * ([AiProviderClients]).
 *
 * Koog's provider clients expose no per-request header hook, so the header is stamped here, in the
 * request pipeline: each request reports the [AiGatewaySession] its caller put in the coroutine
 * context — chat passes its chat session id, so one conversation keeps one id across turns.
 *
 * Requests without one (one-shot edit suggestions, the settings model list) share the per-client
 * `fallbackSessionId`: they send a batch-constant system prompt (see `AiPrompts.editSystem`), so
 * one id per batch helps the gateway's prefix caching.
 *
 * Derived with `HttpClient.config {}`, so it keeps every plugin the receiver already has and shares
 * its engine; the receiver stays the one that owns that engine.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun HttpClient.withOpenCodeSession(): HttpClient {
    val fallbackSessionId = Uuid.random().toString()
    return config {
        install("npn-opencode-session") {
            requestPipeline.intercept(HttpRequestPipeline.Before) {
                context.headers[OPENCODE_SESSION_HEADER] = currentGatewaySessionId() ?: fallbackSessionId
            }
        }
    }
}
