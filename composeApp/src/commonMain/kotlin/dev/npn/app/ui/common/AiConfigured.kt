package dev.npn.app.ui.common

import dev.npn.core.ai.AiAvailability
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.toAiConfig
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Probe whether the AI endpoint is configured, swallowing any failure as `false`. Used to gate
 * AI entry points (chat, tagging, suggestions) that must stay hidden when the endpoint config is
 * missing or unreachable.
 */
suspend fun AiAvailability.isConfiguredOrFalse(): Boolean = runCatchingCancellable { configured() }.getOrDefault(false)

/**
 * Whether the AI endpoint is configured, as it changes: what gates the shell's AI entries and the
 * library's AI actions. A local settings read, so it follows Settings the moment AI is configured or
 * turned off, with no session to reopen.
 */
fun SettingsRepository.aiConfigured(): Flow<Boolean> =
    settings.map { it.toAiConfig().configured }.distinctUntilChanged()
