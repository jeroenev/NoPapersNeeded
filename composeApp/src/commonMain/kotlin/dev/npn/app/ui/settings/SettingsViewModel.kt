package dev.npn.app.ui.settings

import androidx.lifecycle.viewModelScope
import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiModelCatalog
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.ai.AiSelfTest
import dev.npn.core.ai.AiSelfTestReport
import dev.npn.core.ai.AiTagging
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.SettingsSnapshot
import dev.npn.core.app.TrashSettings
import dev.npn.core.app.toAiConfig
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathError
import dev.npn.core.pathrules.StoragePathExpansion
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.watch.liesInLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_folder_access_not_kept_library
import no_papers_needed.composeapp.generated.resources.common_folder_access_not_kept_watched
import no_papers_needed.composeapp.generated.resources.common_folder_in_library_watched
import no_papers_needed.composeapp.generated.resources.common_folder_not_on_device
import no_papers_needed.composeapp.generated.resources.common_save_field_failed
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.common_settings_clear_thumbnails_failed
import no_papers_needed.composeapp.generated.resources.common_settings_move_field_failed
import no_papers_needed.composeapp.generated.resources.common_settings_remove_field_failed
import no_papers_needed.composeapp.generated.resources.common_settings_review_workflow_failed
import no_papers_needed.composeapp.generated.resources.common_settings_storage_rule_stays_off
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnail_cache_empty
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnails_cleared

private const val DEFAULT_STORAGE_PATH_TEMPLATE = "{doc_date:yyyy}/{title}.pdf"

/**
 * What the settings screen shows: this device's settings ([snapshot]; the defaults until they load)
 * and the library's own settings, synced through its journal. In both, an unsaved edit of a setting
 * typed into a field shows first.
 */
data class SettingsUiState(
    val snapshot: SettingsSnapshot = SettingsSnapshot(installationId = ""),
    val storagePathTemplate: String = DEFAULT_STORAGE_PATH_TEMPLATE,
    val storagePathEnabled: Boolean = false,
    val trashRetentionDays: Int = TrashSettings.DEFAULT_TRASH_RETENTION_DAYS,
) {
    /** Whether the endpoint is complete enough to call — what gates the self-test button. */
    val aiConfigured: Boolean get() = snapshot.toAiConfig().configured
}

/**
 * Transient state of the AI self-test: [report] is the last run's verdicts (null until one has
 * been run), and [aiOcrTurnedOff] records that this run switched page reading off, so the screen
 * can say so instead of the setting silently flipping under the user.
 */
data class AiSelfTestState(
    val running: Boolean = false,
    val report: AiSelfTestReport? = null,
    val aiOcrTurnedOff: Boolean = false,
)

/**
 * Transient state of the settings model picker: the model-id list fetched from the configured
 * provider on demand. [error] carries the failure message of the last fetch (manual model entry
 * always stays available, so an error is only a hint).
 */
data class AiModelListState(
    val loading: Boolean = false,
    val models: List<String> = emptyList(),
    val error: UiText? = null,
)

/**
 * The device settings typed into a text field ([SettingsActions.type]). Saving one writes the whole
 * settings file, so what is typed waits as a draft until typing pauses.
 */
enum class TextSetting(
    internal val with: (SettingsSnapshot, String) -> SettingsSnapshot,
) {
    AI_BASE_URL({ snapshot, value -> snapshot.copy(aiBaseUrl = value) }),
    AI_API_KEY({ snapshot, value -> snapshot.copy(aiApiKey = value) }),
    AI_MODEL({ snapshot, value -> snapshot.copy(aiModel = value) }),
}

/** Choice offered by the 1->2 (UNTAGGED_ONLY -> INBOX_WORKFLOW) migration prompt. */
enum class ReviewMigration { MARK_ALL, MARK_TAGGED, MARK_NONE }

/**
 * What the settings panes change. [SettingsViewModel] implements it; a device setting is one
 * [edit], the library's settings and the settings that need logic around them have their own.
 */
interface SettingsActions {
    /**
     * The single write path for a plain setting: the pane edits one field with
     * `edit { it.copy(field = value) }`. Clamping and defaults are applied inside the repository,
     * and a failed write is reported as a message.
     */
    fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot)

    /**
     * Edit the library's storage-path template. Each saved value is a journal event every replica
     * keeps, so a keystroke only updates what the screen shows, and the template is saved once
     * typing pauses. Leaving the screen during that pause still saves it. A template whose save
     * failed stays on screen as typed, for the next edit or [setStoragePathEnabled] to save.
     */
    fun setStoragePathTemplate(template: String)

    /**
     * Arm or disarm the storage-path rule. Arming saves the template shown if it expands, and
     * otherwise says why the rule stays off.
     */
    fun setStoragePathEnabled(enabled: Boolean)

    /**
     * Edit how many days the library's trash keeps a document. Saved as the template is: once
     * typing pauses, or when the screen is left, as each saved value is a journal event.
     */
    fun setTrashRetentionDays(days: Int)

    /**
     * Type [value] into the field of a device [setting]. It shows at once, and is saved once typing
     * pauses, when [doneTyping] says the field lost focus, or when the screen is left: a save writes
     * the whole settings file, too slow to sit between one key and the next.
     */
    fun type(
        setting: TextSetting,
        value: String,
    )

    /** The field of [setting] lost focus: save what was typed in it now. */
    fun doneTyping(setting: TextSetting)

    /**
     * Switch provider and drop what belonged to the previous endpoint: the model list, and the base
     * URL, typed or saved, which the settings screen hides for fixed-URL providers so nobody could
     * clear it there.
     */
    fun setAiProvider(provider: AiProvider)

    /**
     * Fetch the model ids the currently configured provider offers (for the model dropdown).
     * Runs against the live settings values, so the user can load models right after typing an
     * API key. Failures land in [AiModelListState.error]; typing a model id manually always works.
     */
    fun loadAiModels()

    /**
     * Diagnose the configured endpoint: reachability, then an answer, then reading an image
     * ([AiSelfTest]). Runs against the live settings values, like [loadAiModels], so it can be run
     * straight after typing a key.
     *
     * A vision check that ran and failed switches AI page reading off, because that setting spends
     * a request per page on a model that cannot produce text — an unattended, paid no-op. It is
     * only ever switched OFF here, never back on: a model proving it can read images is not a
     * reason to start paying per page.
     */
    fun runAiSelfTest()

    /** Drop all cached thumbnails so they regenerate at the current render quality, and say so. */
    fun clearThumbnailCache()

    fun saveField(def: FieldDef)

    fun removeField(
        key: String,
        clearValues: Boolean,
    )

    /** Swap [key] with its neighbour in registry order; a move past either end is a no-op. */
    fun moveField(
        key: String,
        delta: Int,
    )

    /** Switch to UNTAGGED_ONLY. No migration prompt (the stored reviewed flag is ignored). */
    fun setUntaggedOnlyMode()

    /**
     * Switch to INBOX_WORKFLOW, running the chosen one-shot migration of the reviewed marker.
     * Order: run the migration writes first, then set the mode, so the flag state is ready when
     * the mode flips.
     */
    fun setInboxWorkflowMode(migration: ReviewMigration)

    /**
     * A folder was picked to watch: its location replaces the watched folder, while a folder whose
     * access Android would not keep, or one in the library, leaves the one before and says so.
     */
    fun watchedFolderPicked(pick: FolderPick)
}

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
) : MessagesViewModel(),
    SettingsActions {
    private val content = session.content
    private val reviewConfig = session.reviewConfig
    private val storagePath = session.storagePath
    private val trashRetention = session.trashRetention
    private val fieldRegistry = session.fieldRegistry

    private val typedTemplate =
        TypedSetting<String>(viewModelScope) { template ->
            reporting(Res.string.common_save_setting_failed) { storagePath.setStoragePathTemplate(template) }
        }

    private val typedRetention =
        TypedSetting<Int>(viewModelScope) { days ->
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

    val state: StateFlow<SettingsUiState> =
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
        }.stateIn(viewModelScope, WhileSubscribedTimeout, SettingsUiState())

    val reviewMode: StateFlow<ReviewMode> =
        reviewConfig.reviewMode.stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    private val _aiModels = MutableStateFlow(AiModelListState())

    /** Models offered by the configured provider, fetched on demand via [loadAiModels]. */
    val aiModels: StateFlow<AiModelListState> = _aiModels.asStateFlow()

    private var aiModelsJob: Job? = null

    private val _aiSelfTest = MutableStateFlow(AiSelfTestState())

    /** Verdicts of the last AI self-test, run on demand via [runAiSelfTest]. */
    val aiSelfTest: StateFlow<AiSelfTestState> = _aiSelfTest.asStateFlow()

    private var aiSelfTestJob: Job? = null

    /** The field registry in display order (synced via the journal, like [reviewMode]). */
    val fields: StateFlow<List<FieldDef>> =
        fieldRegistry.fields.stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** Documents carrying a value per field key, for the field manager's usage column and delete warning. */
    val fieldUsage: StateFlow<Map<String, Int>> =
        fieldRegistry.usageCounts().stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    override fun saveField(def: FieldDef) =
        launchReporting(Res.string.common_save_field_failed) { fieldRegistry.upsert(def) }

    override fun removeField(
        key: String,
        clearValues: Boolean,
    ) = launchReporting(Res.string.common_settings_remove_field_failed) { fieldRegistry.remove(key, clearValues) }

    override fun moveField(
        key: String,
        delta: Int,
    ) = launchReporting(Res.string.common_settings_move_field_failed) {
        val keys = fieldRegistry.current().map { it.key }.toMutableList()
        val from = keys.indexOf(key)
        val to = from + delta
        if (from < 0 || to !in keys.indices) return@launchReporting
        keys.add(to, keys.removeAt(from))
        fieldRegistry.reorder(keys)
    }

    override fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) =
        launchReporting(Res.string.common_save_setting_failed) { settings.edit(transform) }

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

    override fun setTrashRetentionDays(days: Int) = typedRetention.type(days)

    override fun type(
        setting: TextSetting,
        value: String,
    ) = typedText.getValue(setting).type(value)

    override fun doneTyping(setting: TextSetting) = typedText.getValue(setting).flush()

    override fun setAiProvider(provider: AiProvider) =
        launchReporting(Res.string.common_save_setting_failed) {
            aiModelsJob?.cancel()
            _aiModels.value = AiModelListState()
            if (settings.settings.first().aiProvider != provider) typedText.getValue(TextSetting.AI_BASE_URL).discard()
            settings.edit { if (it.aiProvider == provider) it else it.copy(aiProvider = provider, aiBaseUrl = "") }
        }

    /** The settings as the screen shows them, typed values included, for a request made from it. */
    private suspend fun shownSettings(): SettingsSnapshot =
        settings.settings.first().withTyped(typedText.map { (setting, typed) -> setting to typed.draft.value })

    override fun loadAiModels() {
        aiModelsJob?.cancel()
        aiModelsJob =
            viewModelScope.launch {
                _aiModels.value = AiModelListState(loading = true)
                val config = shownSettings().toAiConfig()
                _aiModels.value =
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
                _aiSelfTest.value = AiSelfTestState(running = true)
                val config = shownSettings().toAiConfig()
                val report = selfTest(config)
                // The endpoint can be edited while its paid probes run. Its verdict applies only
                // to what was tested, never to the replacement model or its OCR preference.
                if (shownSettings().toAiConfig() != config) {
                    _aiSelfTest.value = AiSelfTestState()
                    return@launch
                }
                val turnedOff = report.provesAiOcrCannotWork && config.ocrAuto && turnOffAiOcr(config)
                val expected = if (turnedOff) config.copy(ocrAuto = false) else config
                _aiSelfTest.value =
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
                    val shown = current.withTyped(typedText.map { (setting, typed) -> setting to typed.draft.value })
                    turnedOff = shown.toAiConfig() == tested
                    if (turnedOff) current.copy(aiOcrAuto = false) else current
                }
            }
        return saved && turnedOff
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
}

/** This snapshot with each typed value of [drafts] in place of the saved one. */
private fun SettingsSnapshot.withTyped(drafts: List<Pair<TextSetting, String?>>): SettingsSnapshot =
    drafts.fold(this) { snapshot, (setting, typed) -> typed?.let { setting.with(snapshot, it) } ?: snapshot }
