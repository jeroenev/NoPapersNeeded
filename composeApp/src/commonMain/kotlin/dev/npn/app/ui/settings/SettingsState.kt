package dev.npn.app.ui.settings

import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.diagnostics.ProblemReportActions
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiSelfTest
import dev.npn.core.ai.AiSelfTestReport
import dev.npn.core.ai.AiSpendSummary
import dev.npn.core.app.LibraryVerificationProgress
import dev.npn.core.app.LibraryVerificationReport
import dev.npn.core.app.ReminderSettings
import dev.npn.core.app.TrashSettings
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailDeviceChoices
import dev.npn.core.model.FieldDef
import dev.npn.core.security.LockTimeout
import dev.npn.core.settings.SettingsSnapshot
import dev.npn.core.settings.toAiConfig
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.sync.SyncHealth
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
    val reminderEnabled: Boolean = false,
    val reminderFieldKeys: Set<String> = emptySet(),
    val reminderLeadDays: Int = ReminderSettings.DEFAULT_LEAD_DAYS,
    /** Whether this device shows reminder notifications; the other reminder settings are the library's. */
    val reminderNotifiesHere: Boolean = true,
    /** The two spend windows the AI pane shows, read from this device's ledger. */
    val aiSpend: AiSpendSummary = AiSpendSummary.EMPTY,
) {
    /** Whether the endpoint is complete enough to call — what gates the self-test button. */
    val aiConfigured: Boolean get() = snapshot.toAiConfig().configured
}

/**
 * What the cloud sync pane shows: this library's remote and schedule on this device ([config]), the
 * live pass ([status]), whether the transfer engine answers ([transport], null until the pane
 * asked), how the last manual "sync now" ended ([outcome], cleared when any new pass starts), and
 * the setup wizard's step ([setup], null while it is closed) on a device that wired one
 * ([setupAvailable]). [health] is the status read as the screen says it, and [conflictDocuments]
 * names the documents its conflicts still point at, when they exist.
 */
data class CloudSyncState(
    val config: CloudSyncConfig = CloudSyncConfig.DISABLED,
    val status: SyncStatus = SyncStatus(),
    val transport: TransportAvailability? = null,
    val outcome: SyncOutcome? = null,
    val setup: CloudSetupState? = null,
    val setupAvailable: Boolean = false,
    val health: SyncHealth = SyncHealth(),
    val conflictDocuments: Map<String, ConflictDocument> = emptyMap(),
)

/** A document a conflict can be linked to, by the title the user knows it by. */
data class ConflictDocument(
    val title: String,
)

/**
 * The library check's state: [running] while it walks, [progress] for the count, and [report] once
 * a run ended cleanly (null until one did, and after a failed or cancelled one).
 */
data class LibraryVerificationState(
    val running: Boolean = false,
    val progress: LibraryVerificationProgress? = null,
    val report: LibraryVerificationReport? = null,
    /** Whether the open result was just copied, for a confirmation the dialog itself shows. */
    val reportCopied: Boolean = false,
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
@Suppress("TooManyFunctions") // Every settings pane's actions, as develop merged them; a split by pane is a follow-up.
interface SettingsActions : ProblemReportActions {
    /**
     * The single write path for a plain setting: the pane edits one field with
     * `edit { it.copy(field = value) }`. Clamping and defaults are applied inside the repository,
     * and a failed write is reported as a message.
     */
    fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot)

    /**
     * Turn the app lock on (which opens its PIN dialog) or off (once the current PIN is confirmed,
     * [confirmAppLockChange]). Off drops the stored hash; the library folder itself is never
     * encrypted, so this changes only how the app opens.
     */
    fun setAppLockEnabled(enabled: Boolean)

    /**
     * Choose how long the app may stay in the background before it locks again, once the current
     * PIN is confirmed.
     */
    fun setAppLockTimeout(timeout: LockTimeout)

    /** Pick a new PIN for the lock that is on, once the current one is confirmed. */
    fun changeAppLockPin()

    /**
     * Store [pin]'s salted hash and enable the lock; the secret itself is never persisted. Replacing
     * the PIN of a lock that is on needs the current one confirmed first ([changeAppLockPin]).
     */
    fun setAppLockPin(pin: String)

    /**
     * Check [secret] as the current PIN for the change the open confirmation asks about, and make
     * the change when it matches. Wrong PINs count against the lock's backoff, as on the lock screen.
     */
    fun confirmAppLockChange(secret: String)

    /** The device's own unlock (biometrics or screen lock) proved the user: make the change. */
    fun confirmAppLockChangeWithDevice()

    /** Lock the app now; it asks for the PIN to come back. */
    fun lockAppNow()

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
     * Edit how many days the library's trash keeps a document; null while the field holds no number
     * in range, which leaves the last one unsaved. Saved only when the field loses focus
     * ([doneTypingTrashRetention]) or the screen is left, never on a pause: the purge acts on each
     * saved value at once and for good, on every device, so a number passed on the way to another
     * (the 1 of 14) must not be saved.
     */
    fun setTrashRetentionDays(days: Int?)

    /** The trash retention field lost focus: save what was typed in it now. */
    fun doneTypingTrashRetention()

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
     * Switch provider and drop what belonged to the previous endpoint: the model list, the base
     * URL, typed or saved, which the settings screen hides for fixed-URL providers so nobody could
     * clear it there, and the API key, typed or saved, which would otherwise go to the new provider's
     * server (the field is masked, so nobody sees it is still filled).
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

    /** Delete this device's AI spend ledger after the user confirms it. */
    fun resetAiSpend()

    /** Ask the transfer engine whether it is usable; the pane asks when it opens. Only the latest probe answers. */
    fun probeTransport()

    /** Run a cloud sync pass now and keep its outcome for the pane; progress streams through the status. */
    fun syncNow()

    /**
     * Check the library folder against itself, reporting progress in [LibraryVerificationState].
     * Nothing is changed; a cancelled or failed run leaves no report.
     */
    fun verifyLibrary()

    /** Stop the library check that is running, if any. */
    fun cancelLibraryVerification()

    /** Close the library check's result, keeping nothing. */
    fun dismissLibraryVerification()

    /** Copy [text] (a rendered library check report) to the system clipboard, and say so. */
    fun copyLibraryVerificationReport(text: String)

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

    /**
     * Save the IMAP account, with [password] when one was typed (null keeps the saved one), and what
     * this device decides for itself ([device]: whether it checks the account, and may log in
     * unencrypted).
     */
    fun saveMailAccount(
        account: MailAccount,
        password: String?,
        device: MailDeviceChoices,
        onSuccess: () -> Unit,
    )

    /** Remove the password from the OS secret store, leaving the account configured otherwise. */
    fun clearMailPassword(onSuccess: () -> Unit)

    /** The email pane opened: read from the keyring whether this device has the password. */
    fun mailPaneOpened()

    /** Connect with these values and report only that it worked, without importing anything. */
    fun testMailConnection(
        account: MailAccount,
        password: String?,
        allowPlain: Boolean,
    )

    /** Check the saved account now, whatever the switch and interval say. */
    fun checkMailNow()

    /** Check now, reading again first the emails earlier checks left in the mailbox. */
    fun retryPassedOverMail()

    /** Stop the running mail check after the message in hand; the next check goes on from there. */
    fun cancelMailCheck()
}

/** The library's reminder settings, split from [SettingsActions] because that interface is already large. */
interface ReminderSettingsActions {
    /** Turn the library's reminders on or off; turning them on chooses the seeded due date when available. */
    fun setRemindersEnabled(enabled: Boolean) = Unit

    /** Watch or stop watching the DATE field [key] for reminders. */
    fun setReminderField(
        key: String,
        selected: Boolean,
    ) = Unit

    /** How many days before a due date the notification window opens; null while the field holds no valid number. */
    fun setReminderLeadDays(days: Int?) = Unit

    /** The lead-days field lost focus: save what was typed in it now. */
    fun doneTypingReminderLead() = Unit

    /** Whether this device shows reminder notifications; other devices keep their own choice. */
    fun setReminderNotifiesHere(notify: Boolean) = Unit
}
