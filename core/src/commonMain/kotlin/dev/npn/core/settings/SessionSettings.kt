package dev.npn.core.settings

import dev.npn.core.ai.AiConfig
import dev.npn.core.model.systemLanguage
import dev.npn.core.ocr.OcrLanguages
import dev.npn.core.sync.CloudSyncConfig

/**
 * The device settings a session reads while it runs. Each is read at the moment it is needed (the
 * OCR language for each document, the AI endpoint on every call), so a change applies without
 * reopening the library.
 */
class SessionSettings(
    val ocrLanguage: suspend () -> String,
    /** What this device kept as settings for library behaviour, adopted by a library that has none. */
    val legacyLibrarySettings: suspend () -> LegacyLibrarySettings = { LegacyLibrarySettings() },
    val aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
    /** The remote, schedule and rclone paths cloud sync reads before every pass. */
    val cloudSync: suspend () -> CloudSyncConfig = { CloudSyncConfig.DISABLED },
) {
    companion object {
        /**
         * The session's view of [settings], the one both apps open their libraries with.
         *
         * [systemLanguage] is the platform's current language, read on every call because it
         * changes while the app runs; it is also what the app's language picker sets, so it stands
         * for the app language wherever the user picked none.
         */
        fun from(
            settings: SettingsRepository,
            systemLanguage: () -> String = ::systemLanguage,
        ): SessionSettings =
            SessionSettings(
                ocrLanguage = { OcrLanguages.effective(settings.settings.value.ocrLanguage, systemLanguage()) },
                legacyLibrarySettings = settings::legacyLibrarySettings,
                aiConfig = { settings.settings.value.toAiConfig(systemLanguage()) },
                cloudSync = { settings.settings.value.toCloudSyncConfig() },
            )
    }
}
