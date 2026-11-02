package dev.npn.app

import androidx.lifecycle.SavedStateHandle
import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.settings.AiModelListState
import dev.npn.app.ui.settings.AiSelfTestState
import dev.npn.app.ui.settings.CloudSetupActions
import dev.npn.app.ui.settings.FieldDialog
import dev.npn.app.ui.settings.ReviewMigration
import dev.npn.app.ui.settings.SettingsPaneActions
import dev.npn.app.ui.settings.SettingsPaneState
import dev.npn.app.ui.settings.SettingsUiState
import dev.npn.app.ui.settings.SettingsViewModel
import dev.npn.app.ui.settings.TextSetting
import dev.npn.core.ai.AiProvider
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.settings.SettingsSnapshot
import dev.npn.core.sync.RcloneProvider

/** A Settings viewmodel that counts the library folder and any folder under it as in the library. */
internal fun settingsVm(
    settings: FakeSettingsRepository = FakeSettingsRepository(),
    session: FakeLibrarySession = FakeLibrarySession(),
    saved: SavedStateHandle = SavedStateHandle(),
) = SettingsViewModel(
    settings,
    session,
    inLibrary = { folder, library -> folder == library || folder.startsWith("$library/") },
    saved = saved,
    newRemoteName = { type -> "$type-1" },
)

/** What a settings pane shows for [snapshot], with no AI models, self-test or fields loaded. */
internal fun paneState(
    snapshot: SettingsSnapshot,
    reviewMode: ReviewMode = ReviewMode.DEFAULT,
) = SettingsPaneState(
    settings = SettingsUiState(snapshot = snapshot),
    aiModels = AiModelListState(),
    aiSelfTest = AiSelfTestState(),
    reviewMode = reviewMode,
    fields = emptyList(),
    fieldUsage = emptyMap(),
)

/** Settings pane actions that do nothing; a test overrides the ones it watches. */
internal open class NoSettingsPaneActions : SettingsPaneActions {
    override fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) = Unit

    override fun setStoragePathTemplate(template: String) = Unit

    override fun setStoragePathEnabled(enabled: Boolean) = Unit

    override fun setTrashRetentionDays(days: Int) = Unit

    override fun type(
        setting: TextSetting,
        value: String,
    ) = Unit

    override fun doneTyping(setting: TextSetting) = Unit

    override fun setAiProvider(provider: AiProvider) = Unit

    override fun loadAiModels() = Unit

    override fun runAiSelfTest() = Unit

    override fun clearThumbnailCache() = Unit

    override fun probeTransport() = Unit

    override fun syncNow() = Unit

    override fun setCloudAutomatic(on: Boolean) = Unit

    override fun setCloudInterval(minutes: Int) = Unit

    override val cloudSetup: CloudSetupActions = NoCloudSetupActions

    override fun saveField(def: FieldDef) = Unit

    override fun removeField(
        key: String,
        clearValues: Boolean,
    ) = Unit

    override fun moveField(
        key: String,
        delta: Int,
    ) = Unit

    override fun setUntaggedOnlyMode() = Unit

    override fun setInboxWorkflowMode(migration: ReviewMigration) = Unit

    override fun watchedFolderPicked(pick: FolderPick) = Unit

    override fun changeLibrary() = Unit

    override fun openAutoMatchRules() = Unit

    override fun openFieldDialog(dialog: FieldDialog?) = Unit
}

/** A setup wizard that does nothing, for a pane rendered without a view model. */
internal object NoCloudSetupActions : CloudSetupActions {
    override fun start() = Unit

    override fun chooseProvider(provider: RcloneProvider) = Unit

    override fun setValue(
        option: String,
        value: String,
    ) = Unit

    override fun submit() = Unit

    override fun cancel() = Unit

    override fun openFolder(name: String) = Unit

    override fun openFolderUp() = Unit

    override fun createFolder(name: String) = Unit

    override fun useFolder() = Unit

    override fun changeFolder() = Unit

    override fun disconnect() = Unit
}
