package dev.npn.app.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiState
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiModelCatalog
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiSelfTest
import dev.npn.core.ai.AiSelfTestReport
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathExpansion
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.security.LockCredential
import dev.npn.core.security.LockCrypto
import dev.npn.core.security.LockTimeout
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.settings.SettingsSnapshot
import dev.npn.core.settings.toAiConfig
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.sync.EngineProblem
import dev.npn.core.sync.SyncFailure
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncStatus
import dev.npn.core.sync.TransportAvailability
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.watch.liesInLibrary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_folder_access_not_kept_library
import no_papers_needed.composeapp.generated.resources.common_folder_access_not_kept_watched
import no_papers_needed.composeapp.generated.resources.common_folder_in_library_watched
import no_papers_needed.composeapp.generated.resources.common_folder_not_on_device
import no_papers_needed.composeapp.generated.resources.common_save_field_failed
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.common_settings_clear_thumbnails_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_disconnect_failed
import no_papers_needed.composeapp.generated.resources.common_settings_move_field_failed
import no_papers_needed.composeapp.generated.resources.common_settings_remove_field_failed
import no_papers_needed.composeapp.generated.resources.common_settings_review_workflow_failed
import no_papers_needed.composeapp.generated.resources.common_settings_storage_rule_stays_off
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnail_cache_empty
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnails_cleared
import no_papers_needed.composeapp.generated.resources.security_pin_too_short

/**
 * Settings screen view model. Device settings go through the one [SettingsRepository.edit]
 * mutation, so a pane edits a field with `edit { it.copy(field = value) }` and there is no
 * per-setting forwarder to keep in sync. The library's own settings (review mode, storage path,
 * trash retention) are written through the session; they, and the device settings that need
 * surrounding logic (provider switch clears the stale model list), keep their own methods.
 *
 * Extends [MessagesViewModel] so a failed write (properties-file I/O, SAF errors) surfaces as a
 * "Could not update …" snackbar instead of being silently swallowed — every write is routed
 * through [launchReporting].
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    session: LibrarySession,
    /**
     * How the AI self-test is run. Injectable so [runAiSelfTest]'s consequence — switching AI page
     * reading off — is testable without an endpoint; production always uses [AiSelfTest.run].
     */
    private val selfTest: suspend (AiConfig) -> AiSelfTestReport = AiSelfTest::run,
    /** Whether a folder lies in the library; see [liesInLibrary]. */
    private val inLibrary: suspend (folder: String, library: String) -> Boolean = ::liesInLibrary,
    /** What the screen's back-stack entry saves for a restored process; a new one opens it afresh. */
    saved: SavedStateHandle = SavedStateHandle(),
    /**
     * The name the cloud setup wizard gives a remote it creates for a provider type: unique per setup,
     * so connecting a second account (or a second library) never overwrites the first one's sign-in.
     */
    newRemoteName: (type: String) -> String = ::newCloudRemoteName,
    /**
     * A scope that outlives this viewmodel (the app's), where [onCleared] forgets a remote the cloud
     * wizard signed in to and never finished with; none, and that is left to the next wizard run.
     */
    private val outliving: CoroutineScope? = null,
    /**
     * Make the salted hash a new app-lock secret is stored as. Injected so a test need not pay the
     * production PBKDF2 cost; production always uses [LockCrypto.create].
     */
    private val createLockCredential: (String) -> LockCredential = { LockCrypto.create(it) },
    /** Where the slow hash runs, never the main thread. */
    private val lockDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MessagesViewModel(),
    SettingsActions {
    private val content = session.content
    private val reviewConfig = session.reviewConfig
    private val storagePath = session.storagePath
    private val trashRetention = session.trashRetention
    private val fieldRegistry = session.fieldRegistry
    private val sync = session.sync

    private val typedTemplate =
        TypedSetting<String>(viewModelScope) { template ->
            reporting(Res.string.common_save_setting_failed) { storagePath.setStoragePathTemplate(template) }
        }

    private val typedRetention =
        TypedSetting<Int>(viewModelScope, savesOnPause = false) { days ->
            reporting(Res.string.common_save_setting_failed) { trashRetention.setTrashRetentionDays(days) }
        }

    private val typedText: Map<TextSetting, TypedSetting<String>> =
        TextSetting.entries.associateWith { setting ->
            TypedSetting(viewModelScope) { value ->
                reporting(Res.string.common_save_setting_failed) { settings.edit { setting.with(it, value) } }
            }
        }

    /** This device's settings with what is typed and not saved yet in place of the saved values. */
    private val typedSettings: Flow<SettingsSnapshot> =
        combine(
            settings.settings,
            combine(typedText.map { (setting, typed) -> typed.draft.map { setting to it } }) { it.toList() },
        ) { snapshot, drafts -> snapshot.withTyped(drafts) }

    /** [snapshot] with what is typed into each text field right now in place of the saved values. */
    private fun withTypedNow(snapshot: SettingsSnapshot): SettingsSnapshot =
        snapshot.withTyped(typedText.map { (setting, typed) -> setting to typed.draft.value })

    private val state: StateFlow<SettingsUiState> =
        combine(
            typedSettings,
            storagePath.storagePath,
            typedTemplate.draft,
            trashRetention.trashRetentionDays,
            typedRetention.draft,
        ) { snapshot, path, template, days, typedDays ->
            SettingsUiState(
                snapshot = snapshot,
                storagePathTemplate = template ?: path.template ?: DEFAULT_STORAGE_PATH_TEMPLATE,
                storagePathEnabled = path.enabled,
                trashRetentionDays = typedDays ?: days,
            )
        }.stateIn(viewModelScope, WhileSubscribedTimeout, SettingsUiState(snapshot = settings.settings.value))

    private val reviewMode: StateFlow<ReviewMode> =
        reviewConfig.reviewMode.stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    /** Models offered by the configured provider, fetched on demand via [loadAiModels]. */
    private val aiModels = MutableStateFlow(AiModelListState())

    private var aiModelsJob: Job? = null

    /** Verdicts of the last AI self-test, run on demand via [runAiSelfTest]. */
    private val aiSelfTest = MutableStateFlow(AiSelfTestState())

    private var aiSelfTestJob: Job? = null

    /** The field registry in display order (synced via the journal, like [reviewMode]). */
    private val fields: StateFlow<List<FieldDef>> =
        fieldRegistry.fields.stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** The library's auto-match rules, for the Automation pane's count of those that are on. */
    private val rules: StateFlow<List<AutoMatchRule>?> =
        session.rules.observeRules().stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /** Documents carrying a value per field key, for the field manager's usage column and delete warning. */
    private val fieldUsage: StateFlow<Map<String, Int>> =
        fieldRegistry.usageCounts().stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    /**
     * The pane picked on a wide window, where picking one is a selection rather than navigation
     * ([SettingsScreen]). Kept here rather than in the screen: the change of the app's language
     * that the Appearance pane makes rebuilds the screen, and must leave that pane open.
     */
    private val pickedState = saved.uiState<SettingsCategoryId?>(PICKED_KEY, null)
    val picked: StateFlow<SettingsCategoryId?> = pickedState.flow

    fun pick(id: SettingsCategoryId?) = pickedState.set(id)

    /**
     * What is typed into the table of contents' search. Kept here, as [picked] is: the list leaves
     * the screen when a compact window shows a pane, and a change of language rebuilds it.
     */
    private val categoryQueryState = saved.uiState(CATEGORY_QUERY_KEY, "")
    val categoryQuery: StateFlow<String> = categoryQueryState.flow

    fun searchCategories(query: String) = categoryQueryState.set(query)

    /** The field manager's open dialog; see [FieldDialog]. */
    private val fieldDialog = saved.uiState<FieldDialog?>(FIELD_DIALOG_KEY, null)

    internal fun openFieldDialog(dialog: FieldDialog?) = fieldDialog.set(dialog)

    /** The app-lock set-or-change dialog, kept through recreation as the field dialog is. */
    private val securityDialog = saved.uiState<SecurityDialog?>(SECURITY_DIALOG_KEY, null)

    internal fun openSecurityDialog(dialog: SecurityDialog?) = securityDialog.set(dialog)

    /**
     * How the last manual sync ended; null while none was asked for, one is under way, or a later
     * (automatic) pass has started since — its result is what the status then shows.
     */
    private val syncOutcome = MutableStateFlow<SyncOutcome?>(null)

    /** Live cloud sync state: what is running, and how the last pass ended. */
    private val syncStatus: Flow<SyncStatus> = sync.status.onEach { if (it.running) syncOutcome.value = null }

    /** Whether the transfer engine answers, probed on demand via [probeTransport]. */
    private val transport = MutableStateFlow<TransportAvailability?>(null)

    private var probeJob: Job? = null

    /** The in-app setup wizard (provider, sign-in, folder) behind the pane's controls. */
    internal val cloud =
        CloudSetupController(
            scope = viewModelScope,
            setup = { sync.setup },
            readRemote = { sync.config.first().remote },
            persistRemote = { remote -> configureCloud { it.copy(remote = remote) } },
            disconnectRemote = ::disconnectCloud,
            newRemoteName = newRemoteName,
        )

    override val cloudSetup: CloudSetupActions get() = cloud

    private val cloudState: Flow<CloudSyncState> =
        combine(
            combine(sync.config, syncStatus, ::Pair),
            transport,
            syncOutcome,
            cloud.state,
        ) { (config, status), engine, outcome, setup ->
            CloudSyncState(config, status, engine, outcome, setup, cloud.available)
        }

    private fun configureCloud(change: (CloudSyncConfig) -> CloudSyncConfig) =
        launchReporting(Res.string.common_save_setting_failed) { sync.configure(change) }

    override fun setCloudAutomatic(on: Boolean) = configureCloud { it.copy(enabled = on) }

    override fun setCloudInterval(minutes: Int) = configureCloud { it.copy(intervalMinutes = minutes) }

    /**
     * Stop syncing this library and forget the remote's sign-in on this device: the remote the wizard
     * created for it goes from the engine's configuration, tokens and all. A connection string has
     * no stored remote to forget.
     */
    private fun disconnectCloud(remote: String) =
        launchReporting(Res.string.common_settings_cloud_disconnect_failed) {
            sync.configure { it.copy(remote = null, enabled = false) }
            val colon = remote.indexOf(':')
            if (colon > 0) sync.setup?.deleteRemote(remote.substring(0, colon))
        }

    /** Everything a settings pane shows, as one value. */
    internal val paneState: StateFlow<SettingsPaneState> =
        combine(
            state,
            aiModels,
            aiSelfTest,
            combine(reviewMode, cloudState, rules, ::Triple),
            combine(fields, fieldUsage, combine(fieldDialog.flow, securityDialog.flow, ::Pair), ::Triple),
        ) { ui, models, selfTest, (mode, cloudState, rules), fieldsAndDialogs ->
            val (defs, usage, dialogs) = fieldsAndDialogs
            val (dialog, security) = dialogs
            SettingsPaneState(
                ui,
                models,
                selfTest,
                mode,
                defs,
                usage,
                dialog,
                cloudState,
                rules,
                security,
            )
        }.stateIn(
            viewModelScope,
            WhileSubscribedTimeout,
            SettingsPaneState(
                state.value,
                aiModels.value,
                aiSelfTest.value,
                reviewMode.value,
                fields.value,
                fieldUsage.value,
                fieldDialog.value,
                rules = rules.value,
                securityDialog = securityDialog.value,
            ),
        )

    override fun saveField(def: FieldDef) =
        launchReporting(Res.string.common_save_field_failed) { fieldRegistry.upsert(def) }

    override fun removeField(
        key: String,
        clearValues: Boolean,
    ) = launchReporting(Res.string.common_settings_remove_field_failed) { fieldRegistry.remove(key, clearValues) }

    override fun moveField(
        key: String,
        delta: Int,
    ) = launchReporting(Res.string.common_settings_move_field_failed) { fieldRegistry.move(key, delta) }

    override fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) =
        launchReporting(Res.string.common_save_setting_failed) { settings.edit(transform) }

    /**
     * Turning the lock on opens the dialog that picks a secret; it is enabled by [setAppLockPin].
     * Turning it off drops the stored hash with it.
     */
    override fun setAppLockEnabled(enabled: Boolean) {
        if (enabled) {
            openSecurityDialog(SecurityDialog.SetPin)
            return
        }
        launchReporting(Res.string.common_save_setting_failed) {
            settings.edit { it.copy(appLock = it.appLock.copy(enabled = false, credential = null)) }
        }
    }

    override fun setAppLockTimeout(timeout: LockTimeout) =
        launchReporting(Res.string.common_save_setting_failed) {
            settings.edit { it.copy(appLock = it.appLock.copy(timeout = timeout)) }
        }

    override fun setAppLockPin(pin: String) {
        if (pin.length < MIN_APP_LOCK_SECRET_LENGTH) {
            message(uiText(Res.string.security_pin_too_short))
            return
        }
        viewModelScope.launch {
            val saved =
                reporting(Res.string.common_save_setting_failed) {
                    // PBKDF2 is deliberately slow; hashing on the main thread would freeze the dialog.
                    // The hashing is inside the report so a rejected input cannot escape as a crash.
                    val credential = withContext(lockDispatcher) { createLockCredential(pin) }
                    settings.edit { it.copy(appLock = it.appLock.copy(enabled = true, credential = credential)) }
                }
            if (saved) openSecurityDialog(null)
        }
    }

    override fun setStoragePathTemplate(template: String) = typedTemplate.type(template)

    override fun setStoragePathEnabled(enabled: Boolean) =
        launchReporting(Res.string.common_save_setting_failed) {
            if (enabled) {
                // Arm the template the screen shows, saved or not: a draft still waiting for its
                // pause, or the default a library without a template is shown. Read here rather than
                // off `state`, which can be stale when nothing is collecting it.
                val template =
                    typedTemplate.draft.value
                        ?: storagePath.storagePath.first().template
                        ?: DEFAULT_STORAGE_PATH_TEMPLATE
                val types = fieldRegistry.current().associate { it.key to it.type }
                val expansion = StoragePathTemplate.expand(template, StoragePathContext.sample(types))
                if (expansion is StoragePathExpansion.Invalid) {
                    message(uiText(Res.string.common_settings_storage_rule_stays_off, expansion.error.text()))
                    return@launchReporting
                }
                storagePath.setStoragePathTemplate(template)
                // Saved: a draft of it, left by a failed save or still waiting for its pause, is done.
                typedTemplate.saved(template)
            }
            storagePath.setStoragePathEnabled(enabled)
        }

    override fun setTrashRetentionDays(days: Int?) =
        if (days ==
            null
        ) {
            typedRetention.withhold()
        } else {
            typedRetention.type(days)
        }

    override fun doneTypingTrashRetention() = typedRetention.flush()

    override fun type(
        setting: TextSetting,
        value: String,
    ) = typedText.getValue(setting).type(value)

    override fun doneTyping(setting: TextSetting) = typedText.getValue(setting).flush()

    override fun setAiProvider(provider: AiProvider) =
        launchReporting(Res.string.common_save_setting_failed) {
            aiModelsJob?.cancel()
            aiModels.value = AiModelListState()
            if (settings.settings.first().aiProvider != provider) {
                typedText.getValue(TextSetting.AI_BASE_URL).discard()
                typedText.getValue(TextSetting.AI_API_KEY).discard()
            }
            settings.edit {
                if (it.aiProvider == provider) it else it.copy(aiProvider = provider, aiBaseUrl = "", aiApiKey = "")
            }
        }

    /** The settings as the screen shows them, typed values included, for a request made from it. */
    private suspend fun shownSettings(): SettingsSnapshot = withTypedNow(settings.settings.first())

    override fun loadAiModels() {
        aiModelsJob?.cancel()
        aiModelsJob =
            viewModelScope.launch {
                aiModels.value = AiModelListState(loading = true)
                val config = shownSettings().toAiConfig()
                aiModels.value =
                    runCatchingCancellable { AiModelCatalog.listModels(config) }.fold(
                        onSuccess = { AiModelListState(models = it) },
                        onFailure = { AiModelListState(error = it.reasonText()) },
                    )
            }
    }

    override fun runAiSelfTest() {
        aiSelfTestJob?.cancel()
        aiSelfTestJob =
            viewModelScope.launch {
                aiSelfTest.value = AiSelfTestState(running = true)
                val config = shownSettings().toAiConfig()
                val report = selfTest(config)
                // The endpoint can be edited while its paid probes run. Its verdict applies only
                // to what was tested, never to the replacement model or its OCR preference.
                if (shownSettings().toAiConfig() != config) {
                    aiSelfTest.value = AiSelfTestState()
                    return@launch
                }
                val turnedOff = report.provesAiOcrCannotWork && config.ocrAuto && turnOffAiOcr(config)
                val expected = if (turnedOff) config.copy(ocrAuto = false) else config
                aiSelfTest.value =
                    if (shownSettings().toAiConfig() == expected) {
                        AiSelfTestState(report = report, aiOcrTurnedOff = turnedOff)
                    } else {
                        AiSelfTestState()
                    }
            }
    }

    /** Switch AI page reading off; true once it is, false (and reported) when the write failed. */
    private suspend fun turnOffAiOcr(tested: AiConfig): Boolean {
        var turnedOff = false
        val saved =
            reporting(Res.string.common_save_setting_failed) {
                settings.edit { current ->
                    // Recheck inside the repository's serialized edit: another settings write may
                    // have landed while this one waited. Include unsaved endpoint typing too.
                    val shown = withTypedNow(current)
                    turnedOff = shown.toAiConfig() == tested
                    if (turnedOff) current.copy(aiOcrAuto = false) else current
                }
            }
        return saved && turnedOff
    }

    override fun probeTransport() {
        probeJob?.cancel()
        probeJob =
            viewModelScope.launch {
                transport.value =
                    runCatchingCancellable { sync.transportAvailability() }.getOrElse { failure ->
                        TransportAvailability.Failed(EngineProblem.ERROR, failure.reason())
                    }
            }
    }

    /** The facade is not supposed to throw, but a UI scope must not be the thing that finds out. */
    override fun syncNow() {
        viewModelScope.launch {
            syncOutcome.value = null
            syncOutcome.value =
                runCatchingCancellable { sync.syncNow() }.getOrElse { failure ->
                    SyncOutcome.Failed(SyncFailure.UNKNOWN, failure.reason())
                }
        }
    }

    override fun clearThumbnailCache() =
        launchReporting(Res.string.common_settings_clear_thumbnails_failed) {
            val cleared = content.clearThumbnailCache()
            message(
                if (cleared > 0) {
                    uiText(Res.plurals.common_settings_thumbnails_cleared, cleared, cleared)
                } else {
                    uiText(Res.string.common_settings_thumbnail_cache_empty)
                },
            )
        }

    /** A library folder was picked that direct mode cannot use: it is not on device storage. */
    fun folderNotOnDevice() = message(uiText(Res.string.common_folder_not_on_device))

    /** A library folder was picked whose access Android would not keep, so the library stays as it was. */
    fun libraryAccessNotKept() = message(uiText(Res.string.common_folder_access_not_kept_library))

    override fun watchedFolderPicked(pick: FolderPick) {
        when (pick) {
            is FolderPick.Location -> watch(pick.value)

            FolderPick.AccessNotKept -> message(uiText(Res.string.common_folder_access_not_kept_watched))

            // No access could watch it, so the pick changes nothing.
            FolderPick.Unusable -> Unit
        }
    }

    /** Watch the folder at [location], unless it lies in the library: its files are the library's already. */
    private fun watch(location: String) =
        launchReporting(Res.string.common_save_setting_failed) {
            val library = settings.settings.first().libraryLocation
            if (library != null && inLibrary(location, library)) {
                message(uiText(Res.string.common_folder_in_library_watched))
            } else {
                settings.edit { it.copy(watchedFolder = location) }
            }
        }

    override fun setUntaggedOnlyMode() =
        launchReporting(Res.string.common_settings_review_workflow_failed) {
            reviewConfig.setReviewMode(ReviewMode.UNTAGGED_ONLY)
        }

    override fun setInboxWorkflowMode(migration: ReviewMigration) =
        launchReporting(Res.string.common_settings_review_workflow_failed) {
            when (migration) {
                ReviewMigration.MARK_ALL -> reviewConfig.markReviewedForMigration(onlyTagged = false)
                ReviewMigration.MARK_TAGGED -> reviewConfig.markReviewedForMigration(onlyTagged = true)
                ReviewMigration.MARK_NONE -> Unit
            }
            reviewConfig.setReviewMode(ReviewMode.INBOX_WORKFLOW)
        }

    override fun onCleared() {
        // Leaving the screen mid-run (a question after sign-in, or the folder step) would leave the
        // remote it signed in to, token and all, in the engine's configuration, where nothing would
        // ever remove it: forget it where the leaving cannot cut that short.
        val setup = sync.setup
        if (setup != null) outliving?.launch { cloud.forgetUnfinished(setup) }
        super.onCleared()
    }

    private companion object {
        const val PICKED_KEY = "settings.picked"
        const val CATEGORY_QUERY_KEY = "settings.categoryQuery"
        const val FIELD_DIALOG_KEY = "settings.fieldDialog"
        const val SECURITY_DIALOG_KEY = "settings.securityDialog"
    }
}

/** This snapshot with each typed value of [drafts] in place of the saved one. */
private fun SettingsSnapshot.withTyped(drafts: List<Pair<TextSetting, String?>>): SettingsSnapshot =
    drafts.fold(this) { snapshot, (setting, typed) -> typed?.let { setting.with(snapshot, it) } ?: snapshot }
