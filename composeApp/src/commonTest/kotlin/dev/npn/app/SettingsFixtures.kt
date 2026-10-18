package dev.npn.app

import androidx.lifecycle.SavedStateHandle
import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.settings.AiModelListState
import dev.npn.app.ui.settings.AiSelfTestState
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

    override fun typeOcrLanguage(text: String) = Unit

    override fun confirmInboxMigration(open: Boolean) = Unit
}
