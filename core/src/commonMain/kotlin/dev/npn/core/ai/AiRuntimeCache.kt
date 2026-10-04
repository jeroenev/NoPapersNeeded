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

    /**
     * The subset of [AiConfig] that actually shapes the built [AiRuntime] (endpoint client + model
     * — see [AiRuntime.build]). Cache identity is keyed on this so tuning a runtime-irrelevant
     * setting like [AiConfig.maxTagsPerDoc], [AiConfig.maxNewTagsPerDoc] or the reasoning levels
     * (applied per call, not per client) does NOT rebuild the client and abort in-flight requests.
     */
    private data class RuntimeKey(
        val enabled: Boolean,
        val provider: AiProvider,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    private fun AiConfig.runtimeKey() = RuntimeKey(enabled, provider, baseUrl, apiKey, model)

    /** The runtime for [config], built now if the cached one was built for another endpoint. */
    suspend fun runtimeFor(config: AiConfig): AiRuntime {
        check(config.configured) { "AI endpoint is not configured (see Settings > AI assistant)" }
        val key = config.runtimeKey()
        return mutex.withLock {
            val current = cached
            current?.takeIf { it.first == key }?.second ?: run {
                // Runtime-relevant config changed: close the stale runtime's HTTP client before
                // swapping. Any in-flight request still holding the old runtime will fail once its
                // client is closed — acceptable, since the endpoint changed out from under it.
                current?.second?.close()
                build(config).also { cached = key to it }
            }
        }
    }

    /**
     * Release the cached runtime's HTTP client. The session calls this when it closes; without it
     * every library swap would leave a client and its engine behind. A call after this simply
     * builds a fresh runtime.
     */
    suspend fun close() =
        mutex.withLock {
            cached?.second?.close()
            cached = null
        }
}
