package dev.npn.core.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one [AiRuntime] a facade keeps, rebuilt only when the part of [AiConfig] that shapes it
 * changes, so its HTTP client is reused across calls.
 *
 * @param build makes a runtime for a config; [AiRuntime.build] in production, which a test can
 *  hand a transport of its own.
 */
internal class AiRuntimeCache(
    private val build: (AiConfig) -> AiRuntime = { AiRuntime.build(it) },
) {
    private val mutex = Mutex()
    private var cached: Pair<RuntimeKey, AiRuntime>? = null

    /** Set by [close], under [mutex]: from then on nothing is built, so nothing is left open. */
    private var closed = false

    /**
     * The subset of [AiConfig] that actually shapes the built [AiRuntime] (endpoint client + model
     * — see [AiRuntime.build]). Cache identity is keyed on this so tuning a runtime-irrelevant
     * setting like [AiConfig.maxTagsPerDoc], [AiConfig.maxNewTagsPerDoc] or the reasoning levels
     * (applied per call, not per client) does NOT rebuild the client and abort in-flight requests.
     * No `enabled`: [runtimeFor] only builds for a configured endpoint, which is always enabled. The
     * URL is the one the client is built for, so a leftover override another provider ignores
     * cannot tell two identical runtimes apart.
     */
    private data class RuntimeKey(
        val provider: AiProvider,
        val effectiveBaseUrl: String,
        val apiKey: String,
        val model: String,
    )

    private fun AiConfig.runtimeKey() = RuntimeKey(provider, effectiveBaseUrl, apiKey, model)

    /**
     * The runtime for [config], built now if the cached one was built for another endpoint. The new
     * one is built first: only once that succeeded is the old one closed and replaced, so a config
     * that cannot build (a malformed URL) throws and leaves the working runtime cached, and open.
     * After [close] it throws [IllegalStateException] ([CLOSED]).
     */
    suspend fun runtimeFor(config: AiConfig): AiRuntime {
        check(config.configured) { "AI endpoint is not configured (see Settings > AI assistant)" }
        val key = config.runtimeKey()
        return mutex.withLock {
            check(!closed) { CLOSED }
            val current = cached
            current?.takeIf { it.first == key }?.second ?: run {
                val built = build(config)
                // Runtime-relevant config changed: close the stale runtime's HTTP client as it is
                // swapped out. Any in-flight request still holding the old runtime will fail once its
                // client is closed — acceptable, since the endpoint changed out from under it.
                current?.second?.close()
                built.also { cached = key to it }
            }
        }
    }

    /**
     * Release the cached runtime's HTTP client, for good. The session calls this when it closes;
     * without it every library swap would leave a client and its engine behind. Closing is final: a
     * call racing the close (an `ask` the session no longer owns) is refused rather than building a
     * client nobody would close.
     */
    suspend fun close() =
        mutex.withLock {
            closed = true
            cached?.second?.close()
            cached = null
        }

    companion object {
        const val CLOSED = "The AI endpoint client is closed"
    }
}
