package dev.npn.app.ui.settings

import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.common.UiText
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiSelfTest
import dev.npn.core.ai.AiSelfTestReport
import dev.npn.core.app.TrashSettings
import dev.npn.core.model.FieldDef
import dev.npn.core.settings.SettingsSnapshot
import dev.npn.core.settings.toAiConfig
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncStatus
import dev.npn.core.sync.TransportAvailability

internal const val DEFAULT_STORAGE_PATH_TEMPLATE = "{doc_date:yyyy}/{title}.pdf"

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
 * What the cloud sync pane shows: this library's remote and schedule on this device ([config]), the
 * live pass ([status]), whether the transfer engine answers ([transport], null until the pane
 * asked), how the last manual "sync now" ended ([outcome], cleared when any new pass starts), and
 * the setup wizard's step ([setup], null while it is closed) on a device that wired one
 * ([setupAvailable]).
 */
data class CloudSyncState(
    val config: CloudSyncConfig = CloudSyncConfig.DISABLED,
    val status: SyncStatus = SyncStatus(),
    val transport: TransportAvailability? = null,
    val outcome: SyncOutcome? = null,
    val setup: CloudSetupState? = null,
    val setupAvailable: Boolean = false,
)

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

    /** Ask the transfer engine whether it is usable; the pane asks when it opens. Only the latest probe answers. */
    fun probeTransport()

    /** Run a cloud sync pass now and keep its outcome for the pane; progress streams through the status. */
    fun syncNow()

    /** Turn this library's automatic passes on or off. */
    fun setCloudAutomatic(on: Boolean)

    /** How often automatic passes run for this library, in minutes. */
    fun setCloudInterval(minutes: Int)

    /** The in-app cloud setup wizard: provider, sign-in, folder. */
    val cloudSetup: CloudSetupActions

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
