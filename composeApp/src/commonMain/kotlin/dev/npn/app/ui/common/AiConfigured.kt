package dev.npn.app.ui.common

import dev.npn.core.ai.AiAvailability
import dev.npn.core.util.runCatchingCancellable

/**
 * Probe whether the AI endpoint is configured, swallowing any failure as `false`. Used to gate
 * AI entry points (chat, tagging, suggestions) that must stay hidden when the endpoint config is
 * missing or unreachable.
 */
suspend fun AiAvailability.isConfiguredOrFalse(): Boolean = runCatchingCancellable { configured() }.getOrDefault(false)
