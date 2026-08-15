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

// A flat contract of one-line per-setting setters; splitting it up would only scatter the
// device-local settings surface across artificial sub-interfaces.
@Suppress("TooManyFunctions")
interface SettingsRepository {
    val settings: Flow<SettingsSnapshot>

    suspend fun setLibraryLocation(location: String?)

    /**
     * App interface language override as an ISO 639-1 code (e.g. "en", "nl"), or null to follow
     * the system locale. Device-local by design: language is a per-user UI preference.
     */
    suspend fun setAppLanguage(lang: String?)

    suspend fun setOcrLanguage(lang: String)

    suspend fun setTrashRetentionDays(days: Int)

    suspend fun setDefaultViewType(v: ViewType)

    suspend fun setLibrarySort(sort: LibrarySort)

    suspend fun setGridColumnWidth(dp: Int)

    suspend fun setLandscapeListColumns(enabled: Boolean)

    /**
     * Folder watched for new PDFs to auto-import (e.g. a scanner app's output folder). Desktop:
     * absolute path; android: SAF tree uri string (persistable permission taken by the caller).
     * Null = feature disabled. Device-local by design: paths/uris never make sense on another
     * device, so this must not sync through the journal.
     */
    suspend fun setWatchedFolder(location: String?)

    /** Whether source files in the watched folder are removed after a successful import. Default false. */
    suspend fun setWatchedFolderDeleteAfterImport(enabled: Boolean)

    /** Storage path template, e.g. `{correspondent}/{doc_date:yyyy}/{title}.pdf`. Null = unset. */
    suspend fun setStoragePathTemplate(template: String?)

    /** Whether automatic storage path rule application is enabled. Default false. */
    suspend fun setStoragePathEnabled(enabled: Boolean)

    /** Durable device-local JSON source for auto-match rules; Room only projects this value. */
    suspend fun setAutoMatchRulesJson(json: String)

    // --- AI assistant (device-local by design: the API key must never sync via the journal) ---

    /** Master switch for all AI features. Default false. */
    suspend fun setAiEnabled(enabled: Boolean)

    /** Provider AI requests go to; [AiProvider.CUSTOM] is the fully manual escape hatch. */
    suspend fun setAiProvider(provider: AiProvider)

    /**
     * Server root override, e.g. `https://api.openai.com` or `http://localhost:11434`. Blank
     * means the provider's default; required only for [AiProvider.CUSTOM].
     */
    suspend fun setAiBaseUrl(url: String)

    /** Bearer token for the endpoint; blank for local servers without auth. */
    suspend fun setAiApiKey(key: String)

    /** Model id as the endpoint knows it, e.g. `gpt-4.1-mini` or `qwen3:8b`. */
    suspend fun setAiModel(model: String)

    /** Reasoning effort for the chat assistant. Default: endpoint behaviour. */
    suspend fun setAiReasoningAssistant(level: AiReasoningLevel)

    /** Reasoning effort for AI-edit suggestion calls. Default: LOW (cheap extraction tasks). */
    suspend fun setAiReasoningEdit(level: AiReasoningLevel)

    /** Max tag suggestions produced per document. Default 8, coerced into [AiTagging.RANGE]. */
    suspend fun setAiMaxTagsPerDoc(value: Int)

    /**
     * Max genuinely-new (non-vocabulary) tags proposed per document. Default 5, coerced into
     * [AiTagging.NEW_TAGS_RANGE] (0 disables proposing new tags entirely).
     */
    suspend fun setAiMaxNewTagsPerDoc(value: Int)

    /**
     * Whether newly OCR'd documents are enriched automatically, without a review step. See
     * [dev.npn.core.ai.AiConfig.autoOnOcr]. Default false.
     */
    suspend fun setAiAutoOnOcr(enabled: Boolean)
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
