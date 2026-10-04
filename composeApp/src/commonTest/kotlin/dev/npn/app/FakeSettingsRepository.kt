package dev.npn.app

import dev.npn.core.ai.AiProvider
import dev.npn.core.app.SettingsCodec
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.SettingsSnapshot
import dev.npn.core.app.ViewType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * Settings fake backed by one snapshot flow. [edit] runs the real [SettingsCodec.normalise] so the
 * clamping and defaults tests see through the fake are identical to production — the fake does not
 * re-derive them.
 */
class FakeSettingsRepository(
    initialDefaultView: ViewType? = ViewType.LIST,
) : SettingsRepository {
    val settingsFlow =
        MutableStateFlow(
            initialDefaultView?.let {
                SettingsSnapshot(installationId = "test-installation", defaultViewType = it)
            },
        )

    override val settings: Flow<SettingsSnapshot> = settingsFlow.filterNotNull()

    fun updateSettings(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        settingsFlow.value = transform(settingsFlow.value ?: defaultSnapshot())
    }

    /** If set, [edit] throws this, as a failed properties-file or DataStore write would. */
    var failEdit: (() -> Nothing)? = null

    override suspend fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        failEdit?.invoke()
        settingsFlow.value = SettingsCodec.normalise(transform(settingsFlow.value ?: defaultSnapshot()))
    }

    private fun defaultSnapshot() =
        SettingsSnapshot(
            installationId = "test-installation",
            defaultViewType = ViewType.LIST,
        )
}

/** Settings with an AI endpoint configured, which every AI action and screen waits for. */
fun aiConfiguredSettings(): FakeSettingsRepository =
    FakeSettingsRepository().apply {
        updateSettings { it.copy(aiEnabled = true, aiProvider = AiProvider.OLLAMA, aiModel = "qwen3:8b") }
    }
