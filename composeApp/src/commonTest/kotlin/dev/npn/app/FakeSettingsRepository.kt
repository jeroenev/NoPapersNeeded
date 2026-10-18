package dev.npn.app

import dev.npn.core.ai.AiProvider
import dev.npn.core.app.ViewType
import dev.npn.core.settings.SettingsCodec
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.settings.SettingsSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings fake backed by one snapshot flow, loaded from the start as the real repositories are.
 * [edit] runs the real [SettingsCodec.normalise] so the clamping and defaults tests see through the
 * fake are identical to production — the fake does not re-derive them.
 */
class FakeSettingsRepository(
    initialDefaultView: ViewType = ViewType.LIST,
) : SettingsRepository {
    val settingsFlow =
        MutableStateFlow(SettingsSnapshot(installationId = "test-installation", defaultViewType = initialDefaultView))

    override val settings: StateFlow<SettingsSnapshot> = settingsFlow.asStateFlow()

    fun updateSettings(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        settingsFlow.value = transform(settingsFlow.value)
    }

    /** If set, [edit] throws this, as a failed properties-file or DataStore write would. */
    var failEdit: (() -> Nothing)? = null
    var editGate: CompletableDeferred<Unit>? = null

    override suspend fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        editGate?.await()
        failEdit?.invoke()
        settingsFlow.value = SettingsCodec.normalise(transform(settingsFlow.value))
    }
}

/** Settings with an AI endpoint configured, which every AI action and screen waits for. */
fun aiConfiguredSettings(): FakeSettingsRepository =
    FakeSettingsRepository().apply {
        updateSettings { it.copy(aiEnabled = true, aiProvider = AiProvider.OLLAMA, aiModel = "qwen3:8b") }
    }
