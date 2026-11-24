package dev.npn.core.settings

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.ai.AiTagging
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SortField
import dev.npn.core.app.ViewType
import dev.npn.core.ocr.OcrLanguages
import dev.npn.core.security.AppLockSettings
import dev.npn.core.security.LockCredential
import dev.npn.core.security.LockTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// ---------- settings (app-global, not per library) ----------

data class SettingsSnapshot(
    /**
     * This installation's identity: it names the installation's journal staging files and tells a
     * restored or copied device database from its own (`DeviceIdentity`). Never part of a backup:
     * Android keeps it in the no-backup directory.
     */
    val installationId: String,
    val libraryLocation: String? = null,
    val appLanguage: String? = null,
    /**
     * Tesseract codes OCR recognises, `+`-joined ([dev.npn.core.ocr.OcrLanguages]); null follows
     * the app language. Only the desktop engine reads it: MLKit's Latin recogniser takes none.
     */
    val ocrLanguage: String? = null,
    val defaultViewType: ViewType = ViewType.GRID,
    val librarySort: LibrarySort = LibrarySort(),
    val gridColumnWidth: Int = GridSizing.DEFAULT_COLUMN_WIDTH,
    val landscapeListColumns: Boolean = false,
    val watchedFolder: String? = null,
    val watchedFolderDeleteAfterImport: Boolean = false,
    /**
     * Android only: pick library and watched folders through the Storage Access Framework instead
     * of as plain device-storage paths. Affects only what the *next* folder choice persists; an
     * already opened library keeps the mode its location string encodes ([LibraryLocations]).
     */
    val useSafStorage: Boolean = false,
    val aiEnabled: Boolean = false,
    val aiProvider: AiProvider = AiProvider.OPENAI,
    val aiBaseUrl: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "",
    val aiReasoningAssistant: AiReasoningLevel = AiReasoningLevel.DEFAULT,
    val aiReasoningEdit: AiReasoningLevel = AiReasoningLevel.LOW,
    val aiMaxTagsPerDoc: Int = AiTagging.DEFAULT_TAGS_PER_DOC,
    val aiMaxNewTagsPerDoc: Int = AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
    val aiAutoOnOcr: Boolean = false,
    val aiOcrAuto: Boolean = false,
    /** Look up the latest published release once per start and offer it when newer. */
    val updateCheckEnabled: Boolean = true,
    /** A release version the user dismissed the update notice for; it is not offered again. */
    val dismissedUpdateVersion: String? = null,
    /** The user closed the library's "Getting started" card; it stays away on this device. */
    val gettingStartedDismissed: Boolean = false,
    /** The user has searched the library on this device: the card ticks it off and the search tip stays away. */
    val searchTried: Boolean = false,
    /** The tip to search a word of a page just read was shown once on this device. */
    val searchTipShown: Boolean = false,
    /**
     * This device's app lock: whether it is on, after how long in the background it asks again,
     * and the salted hash of the unlock secret. It never holds the secret itself.
     */
    val appLock: AppLockSettings = AppLockSettings(),
)

/**
 * Project the AI-related settings into the domain [AiConfig] the facade consumes.
 *
 * [systemLanguage] is the platform's current language, which only the caller can read. It is the
 * fallback for [AiConfig.appLanguage] because [SettingsSnapshot.appLanguage] is null when the user
 * has not overridden the UI language — and "follow the system" is a real language for a prompt, not
 * an absent one. The explicit setting still wins, so a background job that runs without the UI
 * (and so without the locale the composition applies) still prompts in the language the user chose.
 */
fun SettingsSnapshot.toAiConfig(systemLanguage: String? = null): AiConfig =
    AiConfig(
        enabled = aiEnabled,
        provider = aiProvider,
        baseUrl = aiBaseUrl,
        apiKey = aiApiKey,
        model = aiModel,
        reasoningAssistant = aiReasoningAssistant,
        reasoningEdit = aiReasoningEdit,
        maxTagsPerDoc = aiMaxTagsPerDoc,
        maxNewTagsPerDoc = aiMaxNewTagsPerDoc,
        autoOnOcr = aiAutoOnOcr,
        ocrAuto = aiOcrAuto,
        appLanguage = appLanguage ?: systemLanguage,
    )

/**
 * Whether the AI endpoint is configured, as it changes: what gates every AI entry point (the shell's
 * AI entries, the library's and the document's AI actions, the chat). A local settings read, so it
 * follows Settings the moment AI is configured or turned off, with no session or screen to reopen.
 */
fun SettingsRepository.aiConfigured(): Flow<Boolean> = settings.map { it.aiConfigured }.distinctUntilChanged()

/** Whether these settings configure an AI endpoint; [SettingsRepository.aiConfigured] follows it. */
val SettingsSnapshot.aiConfigured: Boolean get() = toAiConfig().configured

/**
 * Device-local, app-global settings (never per library, never synced through the journal — the
 * API key, watched-folder path and UI preferences are meaningless on another device). How a library
 * behaves is not here: its storage-path rule, auto-match rules and trash retention are library
 * settings in its journal ([dev.npn.core.app.StoragePathFacade],
 * [dev.npn.core.app.AutoMatchRuleRepository], [dev.npn.core.app.TrashRetentionFacade]), which
 * every device reads the same.
 *
 * The seam is deliberately narrow: one [settings] snapshot flow to read and one [edit] mutation
 * to write, so the interface is not the storage schema: a new setting is one field on
 * [SettingsSnapshot] and one line in [SettingsCodec], and nothing changes here.
 *
 * Clamping, defaults and key mapping are NOT caller discipline: they live inside the
 * implementations (via the shared [SettingsCodec]), which normalise every snapshot before it is
 * persisted or handed back. A caller that writes `copy(aiMaxTagsPerDoc = 999)` still gets the
 * value coerced into [AiTagging.RANGE].
 */
interface SettingsRepository {
    /**
     * The current settings, already normalised (clamped, defaults filled). Loaded when the
     * repository is built, so [StateFlow.value] is always the real settings, never a placeholder.
     */
    val settings: StateFlow<SettingsSnapshot>

    /**
     * Mutate the settings: [transform] receives the current normalised snapshot and returns the
     * desired one (typically `it.copy(field = value)`). The implementation normalises the result
     * (clamping and defaults) and persists it, then re-emits it on [settings]. This is the single
     * write path; there are no per-field setters to keep in sync.
     */
    suspend fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot)

    /**
     * What this device stored for library behaviour before it moved into each library's journal.
     * Read only to adopt it into a library that has no value of its own yet; nothing writes it.
     */
    suspend fun legacyLibrarySettings(): LegacyLibrarySettings = LegacyLibrarySettings()
}

/**
 * The library behaviour older builds kept as device settings, shared by every library the device
 * opened: the storage-path rule, the auto-match rules blob ([dev.npn.core.rules.AutoMatchRuleJson])
 * and the trash retention in days (null when this device never stored one).
 */
data class LegacyLibrarySettings(
    val storagePathTemplate: String? = null,
    val storagePathEnabled: Boolean = false,
    val autoMatchRulesJson: String? = null,
    val trashRetentionDays: Int? = null,
)

/**
 * The single home for how a [SettingsSnapshot] maps to and from stored key/value strings, and for
 * the clamping/defaults that keep stored values in range. Both platform implementations
 * ([dev.npn.core.settings.SettingsRepositoryImpl] on android and desktop) drive this object, so the key
 * names, parsing quirks (enum fallbacks, empty-string-as-null, the pre-provider CUSTOM migration)
 * and range clamps are defined once instead of re-derived per target. The only thing the platforms
 * own is the storage primitive (SharedPreferences vs a properties file) and how a key is read,
 * written and removed — supplied via [Reader] / [Writer].
 *
 * Persistence keys are frozen here: renaming one silently resets that setting for existing users,
 * so [SettingsKeys] must never change without a migration.
 */
object SettingsCodec {
    /** Reads a stored value for a key, or null when absent. String, int and boolean accessors. */
    interface Reader {
        fun string(key: String): String?

        fun int(key: String): Int?

        fun boolean(key: String): Boolean?
    }

    /** Writes stored values for keys; [remove] clears a key so its default applies on next read. */
    interface Writer {
        fun putString(
            key: String,
            value: String,
        )

        fun putInt(
            key: String,
            value: Int,
        )

        fun putBoolean(
            key: String,
            value: Boolean,
        )

        fun remove(key: String)
    }

    /**
     * Clamp/normalise a snapshot so out-of-range or otherwise invalid values become valid. Applied
     * before persisting and after loading, so neither callers nor stored files can hold an
     * out-of-range value. New per-setting rules go here, not at the call sites.
     */
    fun normalise(snapshot: SettingsSnapshot): SettingsSnapshot =
        snapshot.copy(
            libraryLocation = snapshot.libraryLocation?.ifEmpty { null },
            appLanguage = snapshot.appLanguage?.ifEmpty { null },
            ocrLanguage =
                snapshot.ocrLanguage
                    ?.let(OcrLanguages::parse)
                    ?.ifEmpty { null }
                    ?.let(OcrLanguages::format),
            watchedFolder = snapshot.watchedFolder?.ifEmpty { null },
            gridColumnWidth =
                snapshot.gridColumnWidth.coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH),
            aiMaxTagsPerDoc = snapshot.aiMaxTagsPerDoc.coerceIn(AiTagging.RANGE),
            aiMaxNewTagsPerDoc = snapshot.aiMaxNewTagsPerDoc.coerceIn(AiTagging.NEW_TAGS_RANGE),
            appLock = snapshot.appLock.normalised(),
        )

    /**
     * Build a normalised [SettingsSnapshot] from stored values, filling defaults for absent keys.
     * [installationId] is passed in because minting and persisting a fresh id is a platform-owned
     * side effect (each impl writes it back through its own storage primitive).
     */
    fun load(
        reader: Reader,
        installationId: String,
    ): SettingsSnapshot =
        normalise(
            SettingsSnapshot(
                installationId = installationId,
                libraryLocation = reader.string(SettingsKeys.LIBRARY_LOCATION),
                appLanguage = reader.string(SettingsKeys.APP_LANGUAGE),
                ocrLanguage = reader.string(SettingsKeys.OCR_LANGUAGES) ?: legacyOcrLanguage(reader),
                defaultViewType = enumOr(reader.string(SettingsKeys.DEFAULT_VIEW_TYPE), DEFAULTS.defaultViewType),
                librarySort =
                    LibrarySort(
                        field =
                            SortField.fromWire(reader.string(SettingsKeys.LIBRARY_SORT_FIELD))
                                ?: DEFAULTS.librarySort.field,
                        ascending =
                            reader.boolean(SettingsKeys.LIBRARY_SORT_ASCENDING) ?: DEFAULTS.librarySort.ascending,
                    ),
                gridColumnWidth = reader.int(SettingsKeys.GRID_COLUMN_WIDTH) ?: DEFAULTS.gridColumnWidth,
                landscapeListColumns =
                    reader.boolean(SettingsKeys.LANDSCAPE_LIST_COLUMNS) ?: DEFAULTS.landscapeListColumns,
                watchedFolder = reader.string(SettingsKeys.WATCHED_FOLDER),
                watchedFolderDeleteAfterImport =
                    reader.boolean(SettingsKeys.WATCHED_FOLDER_DELETE_AFTER_IMPORT)
                        ?: DEFAULTS.watchedFolderDeleteAfterImport,
                useSafStorage = reader.boolean(SettingsKeys.USE_SAF_STORAGE) ?: DEFAULTS.useSafStorage,
                aiEnabled = reader.boolean(SettingsKeys.AI_ENABLED) ?: DEFAULTS.aiEnabled,
                aiProvider =
                    enumOrNull<AiProvider>(reader.string(SettingsKeys.AI_PROVIDER))
                        ?: legacyAiProvider(reader.string(SettingsKeys.AI_BASE_URL)),
                aiBaseUrl = reader.string(SettingsKeys.AI_BASE_URL) ?: DEFAULTS.aiBaseUrl,
                aiApiKey = reader.string(SettingsKeys.AI_API_KEY) ?: DEFAULTS.aiApiKey,
                aiModel = reader.string(SettingsKeys.AI_MODEL) ?: DEFAULTS.aiModel,
                aiReasoningAssistant =
                    enumOr(reader.string(SettingsKeys.AI_REASONING_ASSISTANT), AiReasoningLevel.DEFAULT),
                aiReasoningEdit =
                    enumOr(reader.string(SettingsKeys.AI_REASONING_EDIT), AiReasoningLevel.LOW),
                aiMaxTagsPerDoc = reader.int(SettingsKeys.AI_MAX_TAGS_PER_DOC) ?: DEFAULTS.aiMaxTagsPerDoc,
                aiMaxNewTagsPerDoc = reader.int(SettingsKeys.AI_MAX_NEW_TAGS_PER_DOC) ?: DEFAULTS.aiMaxNewTagsPerDoc,
                aiAutoOnOcr = reader.boolean(SettingsKeys.AI_AUTO_ON_OCR) ?: DEFAULTS.aiAutoOnOcr,
                aiOcrAuto = reader.boolean(SettingsKeys.AI_OCR_AUTO) ?: DEFAULTS.aiOcrAuto,
                updateCheckEnabled = reader.boolean(SettingsKeys.UPDATE_CHECK_ENABLED) ?: DEFAULTS.updateCheckEnabled,
                dismissedUpdateVersion = reader.string(SettingsKeys.DISMISSED_UPDATE_VERSION),
                gettingStartedDismissed =
                    reader.flag(SettingsKeys.GETTING_STARTED_DISMISSED, DEFAULTS.gettingStartedDismissed),
                searchTried = reader.flag(SettingsKeys.SEARCH_TRIED, DEFAULTS.searchTried),
                searchTipShown = reader.flag(SettingsKeys.SEARCH_TIP_SHOWN, DEFAULTS.searchTipShown),
                appLock =
                    AppLockSettings(
                        enabled = reader.boolean(SettingsKeys.APP_LOCK_ENABLED) ?: false,
                        timeout = LockTimeout.fromWire(reader.string(SettingsKeys.APP_LOCK_TIMEOUT)),
                        credential = LockCredential.decode(reader.string(SettingsKeys.APP_LOCK_CREDENTIAL)),
                    ),
            ),
        )

    /**
     * Persist a snapshot through [writer]. The snapshot is normalised first, so stored values are
     * always in range. Nullable string settings are removed when null (so their default applies on
     * next read) rather than stored as an empty string. [SettingsSnapshot.installationId] is not
     * written: each platform keeps it where it minted it.
     */
    fun store(
        writer: Writer,
        snapshot: SettingsSnapshot,
    ) {
        val s = normalise(snapshot)
        writer.putOptional(SettingsKeys.LIBRARY_LOCATION, s.libraryLocation)
        writer.putOptional(SettingsKeys.APP_LANGUAGE, s.appLanguage)
        writer.putOptional(SettingsKeys.OCR_LANGUAGES, s.ocrLanguage)
        writer.remove(SettingsKeys.LEGACY_OCR_LANGUAGE)
        writer.putString(SettingsKeys.DEFAULT_VIEW_TYPE, s.defaultViewType.name)
        writer.putString(SettingsKeys.LIBRARY_SORT_FIELD, s.librarySort.field.wire)
        writer.putBoolean(SettingsKeys.LIBRARY_SORT_ASCENDING, s.librarySort.ascending)
        writer.putInt(SettingsKeys.GRID_COLUMN_WIDTH, s.gridColumnWidth)
        writer.putBoolean(SettingsKeys.LANDSCAPE_LIST_COLUMNS, s.landscapeListColumns)
        writer.putOptional(SettingsKeys.WATCHED_FOLDER, s.watchedFolder)
        writer.putBoolean(SettingsKeys.WATCHED_FOLDER_DELETE_AFTER_IMPORT, s.watchedFolderDeleteAfterImport)
        writer.putBoolean(SettingsKeys.USE_SAF_STORAGE, s.useSafStorage)
        writer.putBoolean(SettingsKeys.AI_ENABLED, s.aiEnabled)
        writer.putString(SettingsKeys.AI_PROVIDER, s.aiProvider.name)
        writer.putString(SettingsKeys.AI_BASE_URL, s.aiBaseUrl)
        writer.putString(SettingsKeys.AI_API_KEY, s.aiApiKey)
        writer.putString(SettingsKeys.AI_MODEL, s.aiModel)
        writer.putString(SettingsKeys.AI_REASONING_ASSISTANT, s.aiReasoningAssistant.name)
        writer.putString(SettingsKeys.AI_REASONING_EDIT, s.aiReasoningEdit.name)
        writer.putInt(SettingsKeys.AI_MAX_TAGS_PER_DOC, s.aiMaxTagsPerDoc)
        writer.putInt(SettingsKeys.AI_MAX_NEW_TAGS_PER_DOC, s.aiMaxNewTagsPerDoc)
        writer.putBoolean(SettingsKeys.AI_AUTO_ON_OCR, s.aiAutoOnOcr)
        writer.putBoolean(SettingsKeys.AI_OCR_AUTO, s.aiOcrAuto)
        writer.putBoolean(SettingsKeys.UPDATE_CHECK_ENABLED, s.updateCheckEnabled)
        writer.putOptional(SettingsKeys.DISMISSED_UPDATE_VERSION, s.dismissedUpdateVersion)
        writer.putBoolean(SettingsKeys.GETTING_STARTED_DISMISSED, s.gettingStartedDismissed)
        writer.putBoolean(SettingsKeys.SEARCH_TRIED, s.searchTried)
        writer.putBoolean(SettingsKeys.SEARCH_TIP_SHOWN, s.searchTipShown)
        writer.putBoolean(SettingsKeys.APP_LOCK_ENABLED, s.appLock.enabled)
        writer.putString(SettingsKeys.APP_LOCK_TIMEOUT, s.appLock.timeout.name)
        writer.putOptional(SettingsKeys.APP_LOCK_CREDENTIAL, s.appLock.credential?.encode())
    }

    /**
     * The library behaviour this device stored before it moved into each library's journal. [store]
     * never writes these keys again but leaves them in place, so every library opened afterwards for
     * the first time can still adopt them.
     */
    fun legacyLibrarySettings(reader: Reader): LegacyLibrarySettings =
        LegacyLibrarySettings(
            storagePathTemplate = reader.string(SettingsKeys.STORAGE_PATH_TEMPLATE),
            storagePathEnabled = reader.boolean(SettingsKeys.STORAGE_PATH_ENABLED) ?: false,
            autoMatchRulesJson = reader.string(SettingsKeys.AUTO_MATCH_RULES_JSON),
            trashRetentionDays = reader.int(SettingsKeys.TRASH_RETENTION_DAYS),
        )

    /**
     * The single code stored before automatic existed. Every install wrote one, "eng" by default,
     * so "eng" means the user never chose and reads as automatic: still English, now plus the app
     * language. Anything else was a choice and stays one. [store] drops the key afterwards.
     */
    private fun legacyOcrLanguage(reader: Reader): String? =
        reader.string(SettingsKeys.LEGACY_OCR_LANGUAGE)?.takeUnless { it == OcrLanguages.ENGLISH }

    /** A stored flag, or [default] when absent. */
    private fun Reader.flag(
        key: String,
        default: Boolean,
    ): Boolean = boolean(key) ?: default

    private fun Writer.putOptional(
        key: String,
        value: String?,
    ) {
        if (value == null) remove(key) else putString(key, value)
    }

    /**
     * The provider of settings stored before provider selection existed: a non-blank base URL means
     * the user had configured the old free-form OpenAI-compatible endpoint, which maps exactly onto
     * [AiProvider.CUSTOM]; fresh setups start on the first provider in the picker.
     */
    private fun legacyAiProvider(storedBaseUrl: String?): AiProvider =
        if (storedBaseUrl.isNullOrBlank()) AiProvider.OPENAI else AiProvider.CUSTOM

    private inline fun <reified T : Enum<T>> enumOrNull(stored: String?): T? =
        stored?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private inline fun <reified T : Enum<T>> enumOr(
        stored: String?,
        default: T,
    ): T = enumOrNull<T>(stored) ?: default

    // Field defaults are declared once on SettingsSnapshot; read them off a throwaway instance so
    // they cannot drift from the data class.
    private val DEFAULTS = SettingsSnapshot(installationId = "")
}

internal object SettingsKeys {
    const val LIBRARY_LOCATION = "libraryLocation"
    const val APP_LANGUAGE = "appLanguage"

    /** Superseded by [OCR_LANGUAGES]; only read to migrate it. */
    const val LEGACY_OCR_LANGUAGE = "ocrLanguage"
    const val OCR_LANGUAGES = "ocrLanguages"
    const val DEFAULT_VIEW_TYPE = "defaultViewType"
    const val LIBRARY_SORT_FIELD = "librarySortField"
    const val LIBRARY_SORT_ASCENDING = "librarySortAscending"
    const val GRID_COLUMN_WIDTH = "gridColumnWidth"
    const val LANDSCAPE_LIST_COLUMNS = "landscapeListColumns"
    const val INSTALLATION_ID = "installationId"
    const val WATCHED_FOLDER = "watchedFolder"
    const val WATCHED_FOLDER_DELETE_AFTER_IMPORT = "watchedFolderDeleteAfterImport"
    const val USE_SAF_STORAGE = "useSafStorage"

    // Library behaviour, now in each library's journal; only read to adopt them (legacyLibrarySettings).
    const val STORAGE_PATH_TEMPLATE = "storagePathTemplate"
    const val STORAGE_PATH_ENABLED = "storagePathEnabled"
    const val AUTO_MATCH_RULES_JSON = "autoMatchRulesJson"
    const val TRASH_RETENTION_DAYS = "trashRetentionDays"

    const val AI_ENABLED = "aiEnabled"
    const val AI_PROVIDER = "aiProvider"
    const val AI_BASE_URL = "aiBaseUrl"
    const val AI_API_KEY = "aiApiKey"
    const val AI_MODEL = "aiModel"
    const val AI_REASONING_ASSISTANT = "aiReasoningAssistant"
    const val AI_REASONING_EDIT = "aiReasoningEdit"
    const val AI_MAX_TAGS_PER_DOC = "aiMaxTagsPerDoc"
    const val AI_MAX_NEW_TAGS_PER_DOC = "aiMaxNewTagsPerDoc"
    const val AI_AUTO_ON_OCR = "aiAutoOnOcr"
    const val AI_OCR_AUTO = "aiOcrAuto"
    const val UPDATE_CHECK_ENABLED = "updateCheckEnabled"
    const val DISMISSED_UPDATE_VERSION = "dismissedUpdateVersion"
    const val GETTING_STARTED_DISMISSED = "gettingStartedDismissed"
    const val SEARCH_TRIED = "searchTried"
    const val SEARCH_TIP_SHOWN = "searchTipShown"

    // The app lock. The credential is a salted hash, never the secret.
    const val APP_LOCK_ENABLED = "appLockEnabled"
    const val APP_LOCK_TIMEOUT = "appLockTimeout"
    const val APP_LOCK_CREDENTIAL = "appLockCredential"
}

/** Bounds for the user-adjustable grid cell width (dp). */
object GridSizing {
    const val MIN_COLUMN_WIDTH = 100
    const val MAX_COLUMN_WIDTH = 480
    const val DEFAULT_COLUMN_WIDTH = 160
}
