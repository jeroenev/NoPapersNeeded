package dev.npn.app

import androidx.lifecycle.SavedStateHandle
import dev.npn.app.platform.FolderPick
import dev.npn.app.platform.copyToClipboard
import dev.npn.app.ui.settings.AiModelListState
import dev.npn.app.ui.settings.AiSelfTestState
import dev.npn.app.ui.settings.CloudOAuthApps
import dev.npn.app.ui.settings.CloudSetupActions
import dev.npn.app.ui.settings.FieldDialog
import dev.npn.app.ui.settings.ReviewMigration
import dev.npn.app.ui.settings.SecurityDialog
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.app.ui.settings.SettingsPaneActions
import dev.npn.app.ui.settings.SettingsPaneState
import dev.npn.app.ui.settings.SettingsUiState
import dev.npn.app.ui.settings.SettingsViewModel
import dev.npn.app.ui.settings.TextSetting
import dev.npn.core.ai.AiProvider
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailDeviceChoices
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.security.AppLock
import dev.npn.core.security.LockCredential
import dev.npn.core.security.LockCrypto
import dev.npn.core.security.LockTimeout
import dev.npn.core.settings.SettingsSnapshot
import dev.npn.core.sync.RcloneProvider
import dev.npn.core.util.MonotonicClock
import kotlinx.coroutines.CoroutineScope

/** A Settings viewmodel that counts the library folder and any folder under it as in the library. */
internal fun settingsVm(
    settings: FakeSettingsRepository = FakeSettingsRepository(),
    session: FakeLibrarySession = FakeLibrarySession(),
    saved: SavedStateHandle = SavedStateHandle(),
    outliving: CoroutineScope? = null,
    copy: suspend (String) -> Unit = ::copyToClipboard,
    compute: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    readLog: suspend (Long) -> String = { "" },
    oauthApps: CloudOAuthApps = CloudOAuthApps.NONE,
    lock: AppLock = AppLock(settings.settings.value.appLock, MonotonicClock { 0L }),
    createLockCredential: (
        String,
    ) -> LockCredential = { LockCrypto.create(it, iterations = LockCrypto.MIN_ITERATIONS) },
    lockDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) = SettingsViewModel(
    settings,
    session,
    inLibrary = { folder, library -> folder == library || folder.startsWith("$library/") },
    saved = saved,
    newRemoteName = { type -> "$type-1" },
    oauthApps = oauthApps,
    outliving = outliving,
    copy = copy,
    lock = lock,
    createLockCredential = createLockCredential,
    lockDispatcher = lockDispatcher,
    compute = compute,
    readLog = readLog,
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

    override fun setAppLockEnabled(enabled: Boolean) = Unit

    override fun setEInvoiceFields(enabled: Boolean) = Unit

    override fun setAppLockTimeout(timeout: LockTimeout) = Unit

    override fun setAppLockPin(pin: String) = Unit

    override fun changeAppLockPin() = Unit

    override fun confirmAppLockChange(secret: String) = Unit

    override fun confirmAppLockChangeWithDevice() = Unit

    override fun lockAppNow() = Unit

    override fun setStoragePathTemplate(template: String) = Unit

    override fun setStoragePathEnabled(enabled: Boolean) = Unit

    override fun setTrashRetentionDays(days: Int?) = Unit

    override fun doneTypingTrashRetention() = Unit

    override fun type(
        setting: TextSetting,
        value: String,
    ) = Unit

    override fun doneTyping(setting: TextSetting) = Unit

    override fun setAiProvider(provider: AiProvider) = Unit

    override fun loadAiModels() = Unit

    override fun runAiSelfTest() = Unit

    override fun clearThumbnailCache() = Unit

    override fun resetAiSpend() = Unit

    override fun probeTransport() = Unit

    override fun reportProblem() = Unit

    override fun problemReportHandled() = Unit

    override fun problemReportFailed(failure: Throwable) = Unit

    override fun syncNow() = Unit

    override fun verifyLibrary() = Unit

    override fun cancelLibraryVerification() = Unit

    override fun dismissLibraryVerification() = Unit

    override fun copyLibraryVerificationReport(text: String) = Unit

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

    override fun saveMailAccount(
        account: MailAccount,
        password: String?,
        device: MailDeviceChoices,
        onSuccess: () -> Unit,
    ) = Unit

    override fun clearMailPassword(onSuccess: () -> Unit) = Unit

    override fun mailPaneOpened() = Unit

    override fun testMailConnection(
        account: MailAccount,
        password: String?,
        allowPlain: Boolean,
    ) = Unit

    override fun checkMailNow() = Unit

    override fun retryPassedOverMail() = Unit

    override fun cancelMailCheck() = Unit

    override fun changeLibrary() = Unit

    override fun openAutoMatchRules() = Unit

    override fun openAiSpendKind(kind: String) = Unit

    override fun openCategory(id: SettingsCategoryId) = Unit

    override fun openFieldDialog(dialog: FieldDialog?) = Unit

    override fun openDocument(docId: String) = Unit

    override fun openSecurityDialog(dialog: SecurityDialog?) = Unit
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
