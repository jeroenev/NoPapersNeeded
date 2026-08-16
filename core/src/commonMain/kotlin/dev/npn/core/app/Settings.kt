package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import kotlinx.coroutines.flow.Flow

// ---------- settings (app-global, not per library) ----------

data class SettingsSnapshot(
    /** Stable app installation identity used only for owned staging-file cleanup and diagnostics. */
    val installationId: String,
    val libraryLocation: String? = null,
    val appLanguage: String? = null,
    val ocrLanguage: String = "eng",
    val trashRetentionDays: Int = TrashSettings.DEFAULT_TRASH_RETENTION_DAYS,
    val defaultViewType: ViewType = ViewType.GRID,
    val librarySort: LibrarySort = LibrarySort(),
    val gridColumnWidth: Int = GridSizing.DEFAULT_COLUMN_WIDTH,
    val landscapeListColumns: Boolean = false,
    val watchedFolder: String? = null,
    val watchedFolderDeleteAfterImport: Boolean = false,
    val storagePathTemplate: String? = null,
    val storagePathEnabled: Boolean = false,
    val autoMatchRulesJson: String = "[]",
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
)

/** Project the AI-related settings into the domain [AiConfig] the facade consumes. */
fun SettingsSnapshot.toAiConfig(): AiConfig =
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
    )

/**
 * Device-local, app-global settings (never per library, never synced through the journal — the
 * API key, watched-folder path and UI preferences are meaningless on another device).
 *
 * The seam is deliberately narrow: one [settings] snapshot flow to read and one [edit] mutation
 * to write. It used to be 27 members — a snapshot flow plus 26 one-line setters — so the interface
 * *was* the storage schema and every new setting cost five edits (contract, both platform impls,
 * the fake, the viewmodel). Now a new setting is one field on [SettingsSnapshot] and one line in
 * [SettingsCodec]; nothing changes here.
 *
 * Clamping, defaults and key mapping are NOT caller discipline: they live inside the
 * implementations (via the shared [SettingsCodec]), which normalise every snapshot before it is
 * persisted or handed back. A caller that writes `copy(aiMaxTagsPerDoc = 999)` still gets the
 * value coerced into [AiTagging.RANGE].
 */
interface SettingsRepository {
    /** The current settings, already normalised (clamped, defaults filled). */
    val settings: Flow<SettingsSnapshot>

    /**
     * Mutate the settings: [transform] receives the current normalised snapshot and returns the
     * desired one (typically `it.copy(field = value)`). The implementation normalises the result
     * (clamping and defaults) and persists it, then re-emits it on [settings]. This is the single
     * write path; there are no per-field setters to keep in sync.
     */
    suspend fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot)
}

/**
 * The single home for how a [SettingsSnapshot] maps to and from stored key/value strings, and for
 * the clamping/defaults that keep stored values in range. Both platform implementations
 * ([dev.npn.core.app.SettingsRepositoryImpl] on android and desktop) drive this object, so the key
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
            watchedFolder = snapshot.watchedFolder?.ifEmpty { null },
            storagePathTemplate = snapshot.storagePathTemplate?.ifEmpty { null },
            gridColumnWidth =
                snapshot.gridColumnWidth.coerceIn(GridSizing.MIN_COLUMN_WIDTH, GridSizing.MAX_COLUMN_WIDTH),
            aiMaxTagsPerDoc = snapshot.aiMaxTagsPerDoc.coerceIn(AiTagging.RANGE),
            aiMaxNewTagsPerDoc = snapshot.aiMaxNewTagsPerDoc.coerceIn(AiTagging.NEW_TAGS_RANGE),
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
                ocrLanguage = reader.string(SettingsKeys.OCR_LANGUAGE) ?: DEFAULTS.ocrLanguage,
                trashRetentionDays = reader.int(SettingsKeys.TRASH_RETENTION_DAYS) ?: DEFAULTS.trashRetentionDays,
                defaultViewType = enumOr(reader.string(SettingsKeys.DEFAULT_VIEW_TYPE), DEFAULTS.defaultViewType),
                librarySort =
                    LibrarySort(
                        field = enumOr(reader.string(SettingsKeys.LIBRARY_SORT_FIELD), DEFAULTS.librarySort.field),
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
                storagePathTemplate = reader.string(SettingsKeys.STORAGE_PATH_TEMPLATE),
                storagePathEnabled = reader.boolean(SettingsKeys.STORAGE_PATH_ENABLED) ?: DEFAULTS.storagePathEnabled,
                autoMatchRulesJson = reader.string(SettingsKeys.AUTO_MATCH_RULES_JSON) ?: DEFAULTS.autoMatchRulesJson,
                aiEnabled = reader.boolean(SettingsKeys.AI_ENABLED) ?: DEFAULTS.aiEnabled,
                aiProvider =
                    aiProviderProp(
                        reader.string(SettingsKeys.AI_PROVIDER),
                        reader.string(SettingsKeys.AI_BASE_URL),
                    ),
                aiBaseUrl = reader.string(SettingsKeys.AI_BASE_URL) ?: DEFAULTS.aiBaseUrl,
                aiApiKey = reader.string(SettingsKeys.AI_API_KEY) ?: DEFAULTS.aiApiKey,
                aiModel = reader.string(SettingsKeys.AI_MODEL) ?: DEFAULTS.aiModel,
                aiReasoningAssistant =
                    aiReasoningProp(reader.string(SettingsKeys.AI_REASONING_ASSISTANT), AiReasoningLevel.DEFAULT),
                aiReasoningEdit =
                    aiReasoningProp(reader.string(SettingsKeys.AI_REASONING_EDIT), AiReasoningLevel.LOW),
                aiMaxTagsPerDoc = reader.int(SettingsKeys.AI_MAX_TAGS_PER_DOC) ?: DEFAULTS.aiMaxTagsPerDoc,
                aiMaxNewTagsPerDoc = reader.int(SettingsKeys.AI_MAX_NEW_TAGS_PER_DOC) ?: DEFAULTS.aiMaxNewTagsPerDoc,
                aiAutoOnOcr = reader.boolean(SettingsKeys.AI_AUTO_ON_OCR) ?: DEFAULTS.aiAutoOnOcr,
            ),
        )

    /**
     * Persist a snapshot through [writer]. The snapshot is normalised first, so stored values are
     * always in range. Nullable string settings are removed when null (so their default applies on
     * next read) rather than stored as an empty string. [installationId] is written too so a
     * freshly minted id survives.
     */
    fun store(
        writer: Writer,
        snapshot: SettingsSnapshot,
    ) {
        val s = normalise(snapshot)
        writer.putString(SettingsKeys.INSTALLATION_ID, s.installationId)
        writer.putOptional(SettingsKeys.LIBRARY_LOCATION, s.libraryLocation)
        writer.putOptional(SettingsKeys.APP_LANGUAGE, s.appLanguage)
        writer.putString(SettingsKeys.OCR_LANGUAGE, s.ocrLanguage)
        writer.putInt(SettingsKeys.TRASH_RETENTION_DAYS, s.trashRetentionDays)
        writer.putString(SettingsKeys.DEFAULT_VIEW_TYPE, s.defaultViewType.name)
        writer.putString(SettingsKeys.LIBRARY_SORT_FIELD, s.librarySort.field.name)
        writer.putBoolean(SettingsKeys.LIBRARY_SORT_ASCENDING, s.librarySort.ascending)
        writer.putInt(SettingsKeys.GRID_COLUMN_WIDTH, s.gridColumnWidth)
        writer.putBoolean(SettingsKeys.LANDSCAPE_LIST_COLUMNS, s.landscapeListColumns)
        writer.putOptional(SettingsKeys.WATCHED_FOLDER, s.watchedFolder)
        writer.putBoolean(SettingsKeys.WATCHED_FOLDER_DELETE_AFTER_IMPORT, s.watchedFolderDeleteAfterImport)
        writer.putOptional(SettingsKeys.STORAGE_PATH_TEMPLATE, s.storagePathTemplate)
        writer.putBoolean(SettingsKeys.STORAGE_PATH_ENABLED, s.storagePathEnabled)
        writer.putString(SettingsKeys.AUTO_MATCH_RULES_JSON, s.autoMatchRulesJson)
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
    }

    private fun Writer.putOptional(
        key: String,
        value: String?,
    ) {
        if (value == null) remove(key) else putString(key, value)
    }

    private inline fun <reified T : Enum<T>> enumOr(
        stored: String?,
        default: T,
    ): T = stored?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    // Field defaults are declared once on SettingsSnapshot; read them off a throwaway instance so
    // they cannot drift from the data class.
    private val DEFAULTS = SettingsSnapshot(installationId = "")
}

internal object SettingsKeys {
    const val LIBRARY_LOCATION = "libraryLocation"
    const val APP_LANGUAGE = "appLanguage"
    const val OCR_LANGUAGE = "ocrLanguage"
    const val TRASH_RETENTION_DAYS = "trashRetentionDays"
    const val DEFAULT_VIEW_TYPE = "defaultViewType"
    const val LIBRARY_SORT_FIELD = "librarySortField"
    const val LIBRARY_SORT_ASCENDING = "librarySortAscending"
    const val GRID_COLUMN_WIDTH = "gridColumnWidth"
    const val LANDSCAPE_LIST_COLUMNS = "landscapeListColumns"
    const val INSTALLATION_ID = "installationId"
    const val WATCHED_FOLDER = "watchedFolder"
    const val WATCHED_FOLDER_DELETE_AFTER_IMPORT = "watchedFolderDeleteAfterImport"
    const val STORAGE_PATH_TEMPLATE = "storagePathTemplate"
    const val STORAGE_PATH_ENABLED = "storagePathEnabled"
    const val AUTO_MATCH_RULES_JSON = "autoMatchRulesJson"
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
}

/**
 * Parse a stored [AiProvider] name. When absent (settings written before provider selection
 * existed), a non-blank stored base URL means the user had configured the old free-form
 * OpenAI-compatible endpoint, which maps exactly onto [AiProvider.CUSTOM]; fresh setups start
 * on the first provider in the picker.
 */
internal fun aiProviderProp(
    stored: String?,
    storedBaseUrl: String?,
): AiProvider =
    stored?.takeIf { it.isNotBlank() }?.let { name -> runCatching { AiProvider.valueOf(name) }.getOrNull() }
        ?: if (storedBaseUrl.isNullOrBlank()) AiProvider.OPENAI else AiProvider.CUSTOM

/** Parse a stored [AiReasoningLevel] name, falling back to [default] when absent/unknown. */
internal fun aiReasoningProp(
    stored: String?,
    default: AiReasoningLevel,
): AiReasoningLevel =
    stored?.takeIf { it.isNotBlank() }?.let { name -> runCatching { AiReasoningLevel.valueOf(name) }.getOrNull() }
        ?: default

/** Bounds for the user-adjustable "max suggested tags per document" setting. */
object AiTagging {
    const val MIN_TAGS_PER_DOC = 1
    const val MAX_TAGS_PER_DOC = 15
    const val DEFAULT_TAGS_PER_DOC = 8
    val RANGE = MIN_TAGS_PER_DOC..MAX_TAGS_PER_DOC

    const val MIN_NEW_TAGS_PER_DOC = 0
    const val MAX_NEW_TAGS_PER_DOC = 10
    const val DEFAULT_NEW_TAGS_PER_DOC = 5

    /** Allowed range for the new-tag cap; 0 means never propose brand-new tags. */
    val NEW_TAGS_RANGE = MIN_NEW_TAGS_PER_DOC..MAX_NEW_TAGS_PER_DOC

    /**
     * Bounds for how many documents one bulk-tagging run processes. This cap is per run and
     * adjustable on the AI tagging page (Idle phase); the remainder carries over to the next batch.
     */
    const val MIN_BATCH_DOCS = 1
    const val MAX_BATCH_DOCS = 100
    const val DEFAULT_BATCH_DOCS = 25
    val BATCH_RANGE = MIN_BATCH_DOCS..MAX_BATCH_DOCS
}

object TrashSettings {
    /** Days a trashed document is kept before an automatic purge removes it. */
    const val DEFAULT_TRASH_RETENTION_DAYS = 30
}

/** Bounds for the user-adjustable grid cell width (dp). */
object GridSizing {
    const val MIN_COLUMN_WIDTH = 100
    const val MAX_COLUMN_WIDTH = 480
    const val DEFAULT_COLUMN_WIDTH = 160
}
