package dev.npn.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.ai.AiModelCatalog
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.app.AiTagging
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReviewConfigFacade
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.TrashSettings
import dev.npn.core.app.ViewType
import dev.npn.core.app.toAiConfig
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.StandardField
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val DEFAULT_STORAGE_PATH_TEMPLATE = "{doc_date:yyyy}/{title}.pdf"

data class SettingsUiState(
    val libraryLocation: String? = null,
    val appLanguage: String? = null,
    val ocrLanguage: String = "eng",
    val trashRetentionDays: Int = TrashSettings.DEFAULT_TRASH_RETENTION_DAYS,
    val defaultViewType: ViewType = ViewType.GRID,
    val landscapeListColumns: Boolean = false,
    val watchedFolder: String? = null,
    val watchedFolderDeleteAfterImport: Boolean = false,
    val storagePathTemplate: String = DEFAULT_STORAGE_PATH_TEMPLATE,
    val storagePathEnabled: Boolean = false,
    val aiEnabled: Boolean = false,
    val aiProvider: AiProvider = AiProvider.OPENAI,
    val aiBaseUrl: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "",
    val aiReasoningAssistant: AiReasoningLevel = AiReasoningLevel.DEFAULT,
    val aiReasoningEdit: AiReasoningLevel = AiReasoningLevel.LOW,
    val aiMaxTagsPerDoc: Int = AiTagging.DEFAULT_TAGS_PER_DOC,
    val aiMaxNewTagsPerDoc: Int = AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
)

/**
 * Transient state of the settings model picker: the model-id list fetched from the configured
 * provider on demand. [error] carries the failure message of the last fetch (manual model entry
 * always stays available, so an error is only a hint).
 */
data class AiModelListState(
    val loading: Boolean = false,
    val models: List<String> = emptyList(),
    val error: String? = null,
)

data class StoragePathError(
    val kind: StoragePathErrorKind,
    val argument: String? = null,
)

enum class StoragePathErrorKind {
    UNCLOSED_PLACEHOLDER,
    UNEXPECTED_CLOSING_BRACE,
    DATE_FORMAT_ONLY,
    UNKNOWN_VARIABLE,
    BLANK,
    ABSOLUTE,
    BACKSLASH,
    TRAVERSAL,
    RESERVED_DIRECTORY,
    WHITESPACE,
    INVALID_SEGMENT,
    INVALID,
}

/** Choice offered by the 1->2 (UNTAGGED_ONLY -> INBOX_WORKFLOW) migration prompt. */
enum class ReviewMigration { MARK_ALL, MARK_TAGGED, MARK_NONE }

// Mirrors SettingsRepository's flat per-setting setter surface (one-liners); splitting the
// screen's viewmodel apart would not make it simpler.
@Suppress("TooManyFunctions")
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val content: DocContentFacade,
    private val reviewConfig: ReviewConfigFacade,
) : ViewModel() {
    constructor(settings: SettingsRepository, session: LibrarySession) :
        this(settings, session.content, session.reviewConfig)

    val state: StateFlow<SettingsUiState> =
        settings.settings
            .map { snapshot ->
                val template = snapshot.storagePathTemplate ?: DEFAULT_STORAGE_PATH_TEMPLATE
                SettingsUiState(
                    libraryLocation = snapshot.libraryLocation,
                    appLanguage = snapshot.appLanguage,
                    ocrLanguage = snapshot.ocrLanguage,
                    trashRetentionDays = snapshot.trashRetentionDays,
                    defaultViewType = snapshot.defaultViewType,
                    landscapeListColumns = snapshot.landscapeListColumns,
                    watchedFolder = snapshot.watchedFolder,
                    watchedFolderDeleteAfterImport = snapshot.watchedFolderDeleteAfterImport,
                    storagePathTemplate = template,
                    storagePathEnabled = snapshot.storagePathEnabled,
                    aiEnabled = snapshot.aiEnabled,
                    aiProvider = snapshot.aiProvider,
                    aiBaseUrl = snapshot.aiBaseUrl,
                    aiApiKey = snapshot.aiApiKey,
                    aiModel = snapshot.aiModel,
                    aiReasoningAssistant = snapshot.aiReasoningAssistant,
                    aiReasoningEdit = snapshot.aiReasoningEdit,
                    aiMaxTagsPerDoc = snapshot.aiMaxTagsPerDoc,
                    aiMaxNewTagsPerDoc = snapshot.aiMaxNewTagsPerDoc,
                )
            }.stateIn(viewModelScope, WhileSubscribedTimeout, SettingsUiState())

    val reviewMode: StateFlow<ReviewMode> =
        reviewConfig.reviewMode.stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    private val _aiModels = MutableStateFlow(AiModelListState())

    /** Models offered by the configured provider, fetched on demand via [loadAiModels]. */
    val aiModels: StateFlow<AiModelListState> = _aiModels.asStateFlow()

    private var aiModelsJob: Job? = null

    /** Library-wide visible standard fields (synced via the journal, like [reviewMode]). */
    val standardFields: StateFlow<Set<StandardField>> =
        reviewConfig.standardFields.stateIn(viewModelScope, WhileSubscribedTimeout, StandardField.DEFAULT)

    fun setStandardFieldEnabled(
        field: StandardField,
        enabled: Boolean,
    ) = viewModelScope.launch {
        val current = reviewConfig.standardFields.first()
        reviewConfig.setStandardFields(if (enabled) current + field else current - field)
    }

    fun setOcrLanguage(lang: String) = viewModelScope.launch { settings.setOcrLanguage(lang) }

    fun setAppLanguage(lang: String?) = viewModelScope.launch { settings.setAppLanguage(lang) }

    fun setTrashRetentionDays(days: Int) = viewModelScope.launch { settings.setTrashRetentionDays(days) }

    fun setDefaultViewType(v: ViewType) = viewModelScope.launch { settings.setDefaultViewType(v) }

    fun setLandscapeListColumns(enabled: Boolean) = viewModelScope.launch { settings.setLandscapeListColumns(enabled) }

    fun setWatchedFolder(location: String?) = viewModelScope.launch { settings.setWatchedFolder(location) }

    fun setWatchedFolderDeleteAfterImport(enabled: Boolean) =
        viewModelScope.launch { settings.setWatchedFolderDeleteAfterImport(enabled) }

    fun setStoragePathTemplate(template: String) = viewModelScope.launch { settings.setStoragePathTemplate(template) }

    fun setStoragePathEnabled(enabled: Boolean) =
        viewModelScope.launch {
            // Re-derive validity from the stored template rather than the projected UI state, which
            // can be the stale default when nothing is currently collecting `state`.
            val valid =
                if (!enabled) {
                    true
                } else {
                    val template = settings.settings.first().storagePathTemplate ?: DEFAULT_STORAGE_PATH_TEMPLATE
                    StoragePathTemplate.expand(template, VALIDATION_CONTEXT).isSuccess
                }
            if (valid) settings.setStoragePathEnabled(enabled)
        }

    fun setAiEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAiEnabled(enabled) }

    /** Switch provider and drop the stale model list (it belongs to the previous endpoint). */
    fun setAiProvider(provider: AiProvider) =
        viewModelScope.launch {
            aiModelsJob?.cancel()
            _aiModels.value = AiModelListState()
            settings.setAiProvider(provider)
        }

    fun setAiBaseUrl(url: String) = viewModelScope.launch { settings.setAiBaseUrl(url) }

    fun setAiApiKey(key: String) = viewModelScope.launch { settings.setAiApiKey(key) }

    fun setAiModel(model: String) = viewModelScope.launch { settings.setAiModel(model) }

    fun setAiReasoningAssistant(level: AiReasoningLevel) =
        viewModelScope.launch { settings.setAiReasoningAssistant(level) }

    fun setAiReasoningEdit(level: AiReasoningLevel) = viewModelScope.launch { settings.setAiReasoningEdit(level) }

    /**
     * Fetch the model ids the currently configured provider offers (for the model dropdown).
     * Runs against the live settings values, so the user can load models right after typing an
     * API key. Failures land in [AiModelListState.error]; typing a model id manually always works.
     */
    fun loadAiModels() {
        aiModelsJob?.cancel()
        aiModelsJob =
            viewModelScope.launch {
                _aiModels.value = AiModelListState(loading = true)
                val config = settings.settings.first().toAiConfig()
                try {
                    _aiModels.value = AiModelListState(models = AiModelCatalog.listModels(config))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _aiModels.value = AiModelListState(error = e.message ?: e::class.simpleName ?: "error")
                }
            }
    }

    fun setAiMaxTagsPerDoc(value: Int) = viewModelScope.launch { settings.setAiMaxTagsPerDoc(value) }

    fun setAiMaxNewTagsPerDoc(value: Int) = viewModelScope.launch { settings.setAiMaxNewTagsPerDoc(value) }

    /**
     * Drop all cached thumbnails so they regenerate at the current render quality. Invokes [onDone]
     * with the number cleared (for a confirmation snackbar).
     */
    fun clearThumbnailCache(onDone: (Int) -> Unit) =
        viewModelScope.launch {
            onDone(content.clearThumbnailCache())
        }

    /** Switch to UNTAGGED_ONLY. No migration prompt (the stored reviewed flag is ignored). */
    fun setUntaggedOnlyMode() = viewModelScope.launch { reviewConfig.setReviewMode(ReviewMode.UNTAGGED_ONLY) }

    /**
     * Switch to INBOX_WORKFLOW, running the chosen one-shot migration of the reviewed marker.
     * Order: run the migration writes first, then set the mode, so the flag state is ready when
     * the mode flips.
     */
    fun setInboxWorkflowMode(migration: ReviewMigration) =
        viewModelScope.launch {
            when (migration) {
                ReviewMigration.MARK_ALL -> reviewConfig.markReviewedForMigration(onlyTagged = false)
                ReviewMigration.MARK_TAGGED -> reviewConfig.markReviewedForMigration(onlyTagged = true)
                ReviewMigration.MARK_NONE -> Unit
            }
            reviewConfig.setReviewMode(ReviewMode.INBOX_WORKFLOW)
        }

    private companion object {
        val VALIDATION_CONTEXT =
            StoragePathContext(
                title = "sample-title",
                docDate = "2026-07-20",
                docType = "sample-type",
                correspondent = "sample-correspondent",
                tags = listOf("sample-tag"),
                originalFileName = "scan.pdf",
            )
    }
}

internal data class StoragePathEvaluation(
    val preview: String?,
    val error: StoragePathError?,
)

internal fun evaluateStoragePath(
    template: String,
    context: StoragePathContext,
): StoragePathEvaluation {
    val result = StoragePathTemplate.expand(template, context)
    return StoragePathEvaluation(
        preview = result.getOrNull(),
        error = result.exceptionOrNull()?.message?.toStoragePathError(),
    )
}

private fun String.toStoragePathError(): StoragePathError =
    when {
        startsWith("Unclosed placeholder starting at ") -> {
            StoragePathError(
                StoragePathErrorKind.UNCLOSED_PLACEHOLDER,
                removePrefix("Unclosed placeholder starting at "),
            )
        }

        startsWith("Unexpected '}' at ") -> {
            StoragePathError(
                StoragePathErrorKind.UNEXPECTED_CLOSING_BRACE,
                removePrefix("Unexpected '}' at "),
            )
        }

        this == "Formatting is only supported for doc_date" -> {
            StoragePathError(StoragePathErrorKind.DATE_FORMAT_ONLY, "doc_date")
        }

        startsWith("Unknown template variable '") -> {
            StoragePathError(
                StoragePathErrorKind.UNKNOWN_VARIABLE,
                removePrefix("Unknown template variable '").removeSuffix("'"),
            )
        }

        this == "Path must not be blank" -> {
            StoragePathError(StoragePathErrorKind.BLANK)
        }

        this == "Absolute paths are not allowed" -> {
            StoragePathError(StoragePathErrorKind.ABSOLUTE)
        }

        this == "Backslashes are not allowed" -> {
            StoragePathError(StoragePathErrorKind.BACKSLASH)
        }

        this == "Path traversal is not allowed" -> {
            StoragePathError(StoragePathErrorKind.TRAVERSAL)
        }

        this == "The .npn directory is reserved" -> {
            StoragePathError(StoragePathErrorKind.RESERVED_DIRECTORY, ".npn")
        }

        this == "Path segments may not start or end with whitespace" -> {
            StoragePathError(StoragePathErrorKind.WHITESPACE)
        }

        startsWith("Invalid filename segment '") -> {
            StoragePathError(
                StoragePathErrorKind.INVALID_SEGMENT,
                removePrefix("Invalid filename segment '").removeSuffix("'"),
            )
        }

        else -> {
            StoragePathError(StoragePathErrorKind.INVALID)
        }
    }
