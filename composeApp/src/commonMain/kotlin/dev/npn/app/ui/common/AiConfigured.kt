package dev.npn.app.ui.common

import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.toAiConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Whether the AI endpoint is configured, as it changes: what gates every AI entry point (the shell's
 * AI entries, the library's and the document's AI actions, the chat). A local settings read, so it
 * follows Settings the moment AI is configured or turned off, with no session or screen to reopen.
 */
fun SettingsRepository.aiConfigured(): Flow<Boolean> =
    settings.map { it.toAiConfig().configured }.distinctUntilChanged()
