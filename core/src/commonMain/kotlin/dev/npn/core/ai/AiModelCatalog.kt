package dev.npn.core.ai

/**
 * Fetches the model ids a configured endpoint offers, for the settings model picker. Stateless:
 * each call builds a short-lived client from [AiConfig] (provider + base URL + API key; the
 * configured model is irrelevant) and releases it afterwards. Errors (unreachable server, bad
 * key, gateways without a models endpoint) propagate to the caller, which surfaces them as a
 * settings-screen hint — manual model entry always remains available.
 */
object AiModelCatalog {
    suspend fun listModels(config: AiConfig): List<String> = AiRuntime.build(config).use { it.listModelIds() }
}
