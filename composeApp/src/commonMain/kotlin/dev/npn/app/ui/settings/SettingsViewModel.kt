package dev.npn.app.ui.settings

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiModelCatalog
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.ai.AiSelfTest
import dev.npn.core.ai.AiSelfTestReport
import dev.npn.core.app.AiTagging
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReviewConfigFacade
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.SettingsSnapshot
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
    val aiAutoOnOcr: Boolean = false,
    val aiOcrAuto: Boolean = false,
    /** Whether the endpoint is complete enough to call — what gates the self-test button. */
    val aiConfigured: Boolean = false,
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

/**
 * Settings screen view model. Writes go through the one [SettingsRepository.edit] mutation, so the
 * screen edits a field with `vm.edit { it.copy(field = value) }` and there is no per-setting
 * forwarder to keep in sync. The few settings that need surrounding logic (provider switch clears
 * the stale model list, storage-path enable is gated on template validity, the review-mode
 * migration) keep their own methods.
 *
 * Extends [MessagesViewModel] so a failed write (properties-file I/O, SAF errors) surfaces as a
 * "Could not update …" snackbar instead of being silently swallowed — every write is routed
 * through [launchReporting].
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val content: DocContentFacade,
    private val reviewConfig: ReviewConfigFacade,
    /**
     * How the AI self-test is run. Injectable so [runAiSelfTest]'s consequence — switching AI page
     * reading off — is testable without an endpoint; production always uses [AiSelfTest.run].
     */
    private val selfTest: suspend (AiConfig) -> AiSelfTestReport = AiSelfTest::run,
) : MessagesViewModel() {
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
                    aiAutoOnOcr = snapshot.aiAutoOnOcr,
                    aiOcrAuto = snapshot.aiOcrAuto,
                    aiConfigured = snapshot.toAiConfig().configured,
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

    /** Library-wide visible standard fields (synced via the journal, like [reviewMode]). */
    val standardFields: StateFlow<Set<StandardField>> =
        reviewConfig.standardFields.stateIn(viewModelScope, WhileSubscribedTimeout, StandardField.DEFAULT)

    fun setStandardFieldEnabled(
        field: StandardField,
        enabled: Boolean,
    ) = launchReporting(UiAction.UPDATE) {
        val current = reviewConfig.standardFields.first()
        reviewConfig.setStandardFields(if (enabled) current + field else current - field)
    }

    /**
     * The single write path for a plain setting: the screen edits one field with
     * `vm.edit { it.copy(field = value) }`. Clamping and defaults are applied inside the repository,
     * and a failed write is reported via the inherited [messages] snackbar.
     */
    fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) =
        launchReporting(UiAction.UPDATE) { settings.edit(transform) }

    fun setStoragePathEnabled(enabled: Boolean) =
        launchReporting(UiAction.UPDATE) {
            // Re-derive validity from the stored template rather than the projected UI state, which
            // can be the stale default when nothing is currently collecting `state`.
            val valid =
                if (!enabled) {
                    true
                } else {
                    val template = settings.settings.first().storagePathTemplate ?: DEFAULT_STORAGE_PATH_TEMPLATE
                    StoragePathTemplate.expand(template, VALIDATION_CONTEXT).isSuccess
                }
            if (valid) settings.edit { it.copy(storagePathEnabled = enabled) }
        }

    /** Switch provider and drop the stale model list (it belongs to the previous endpoint). */
    fun setAiProvider(provider: AiProvider) =
        launchReporting(UiAction.UPDATE) {
            aiModelsJob?.cancel()
            _aiModels.value = AiModelListState()
            settings.edit { it.copy(aiProvider = provider) }
        }

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
    fun runAiSelfTest() {
        aiSelfTestJob?.cancel()
        aiSelfTestJob =
            viewModelScope.launch {
                _aiSelfTest.value = AiSelfTestState(running = true)
                val config = settings.settings.first().toAiConfig()
                val report = selfTest(config)
                val turnOffAiOcr = report.provesAiOcrCannotWork && config.ocrAuto
                if (turnOffAiOcr) settings.edit { it.copy(aiOcrAuto = false) }
                _aiSelfTest.value = AiSelfTestState(report = report, aiOcrTurnedOff = turnOffAiOcr)
            }
    }

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
