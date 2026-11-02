package dev.npn.app.ui.onboarding

import androidx.lifecycle.viewModelScope
import dev.npn.app.platform.OcrModelSource
import dev.npn.app.platform.OcrModels
import dev.npn.app.platform.ScanResult
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.asUiText
import dev.npn.app.ui.common.text
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.settings.cloudFolderLabel
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.ImportTally
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.IndexProgress
import dev.npn.core.ocr.OcrLanguages
import dev.npn.core.ocr.OcrState
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.sync.SyncStatus
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.common_scan_capture_failed
import no_papers_needed.composeapp.generated.resources.common_scan_import_failed
import no_papers_needed.composeapp.generated.resources.library_import_failed
import org.jetbrains.compose.resources.StringResource

/** The way through first run: a library of one's own, or one another device already uses. */
enum class FirstRunPath { NEW, CONNECT }

/** A step of first run once its library is open. */
enum class FirstRunStep {
    /** Which languages text recognition reads; only where the platform asks (desktop). */
    LANGUAGES,

    /** Add a first document to a new library. */
    FIRST_DOCUMENT,

    /** Watch a library another device uses come in: the rescan, and the cloud's first pass. */
    CONNECTING,
}

/** The steps of [this] path, the language step first where the platform asks for it ([askLanguages]). */
fun FirstRunPath.steps(askLanguages: Boolean): List<FirstRunStep> =
    listOfNotNull(
        FirstRunStep.LANGUAGES.takeIf { askLanguages },
        if (this == FirstRunPath.NEW) FirstRunStep.FIRST_DOCUMENT else FirstRunStep.CONNECTING,
    )

/** Where first run leaves the user. */
sealed interface FirstRunExit {
    /** The library. */
    data object Library : FirstRunExit

    /** The document just added, and how far text recognition is with it ([text]). */
    data class Document(
        val docId: String,
        val text: FirstDocumentText,
    ) : FirstRunExit

    /** Settings, where a folder is set up to be watched. */
    data object WatchFolder : FirstRunExit
}

/** Where text recognition is with the first document. */
enum class FirstDocumentText {
    /** Still queued: its pages are not searchable yet. */
    READING,

    /** Its text is in the index: a word from its pages finds it. */
    READ,

    /** No text came of it (recognition failed, or never ran): a search will not find it by its words. */
    UNREADABLE,
    ;

    companion object {
        fun of(state: OcrState): FirstDocumentText =
            when (state) {
                OcrState.PENDING -> READING
                OcrState.DONE, OcrState.ADOPTED -> READ
                OcrState.FAILED, OcrState.NONE -> UNREADABLE
            }
    }
}

/** The first document, as it goes into the library. */
sealed interface FirstDocument {
    data object None : FirstDocument

    data object Adding : FirstDocument

    /**
     * [docId] is in the library, called [title], with its [text] as far as text recognition got;
     * [alreadyThere] when the library held it before.
     */
    data class Added(
        val docId: String,
        val title: String,
        val text: FirstDocumentText,
        val alreadyThere: Boolean = false,
    ) : FirstDocument
}

/**
 * The languages text recognition is to read: [selected] ones, checked against the [models] this
 * device has (null while they are being asked for).
 */
data class OcrLanguageChoice(
    val selected: List<String>,
    val models: OcrModels? = null,
) {
    /** The languages on offer: the ones the app ships models for, then any other selected. */
    val offered: List<String> get() = (OcrLanguages.BUNDLED + selected).distinct()

    /** Whether there is no Tesseract at all. */
    val tesseractMissing: Boolean get() = models != null && models.installed == null

    /** Selected languages whose model is not installed. */
    val missing: List<String>
        get() {
            if (models == null) return emptyList()
            val installed = models.installed ?: return selected
            return selected.filterNot { it in installed }
        }
}

/** What first run shows once the library is open. */
data class FirstRunState(
    /** Documents the library shows, null until known. */
    val documents: Int? = null,
    /** The rescan reading the library folder, null when none runs. */
    val scan: IndexProgress? = null,
    val sync: SyncStatus = SyncStatus(),
    /** The library's cloud folder as the user knows it, null without one. */
    val cloudFolder: String? = null,
    val firstDocument: FirstDocument = FirstDocument.None,
    val languages: OcrLanguageChoice? = null,
)

/**
 * First run's steps once the library is open ([FirstRunStep]): the languages text recognition reads,
 * the first document going in and having its text read, and a library another device uses coming
 * in. [appLanguage] is the language the app shows, which the automatic language choice follows;
 * [readOcrModels] asks which models the device has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunViewModel(
    private val session: LibrarySession,
    private val settings: SettingsRepository,
    private val appLanguage: String?,
    private val readOcrModels: suspend () -> OcrModels?,
) : MessagesViewModel() {
    override val log: NpnLog get() = session.log

    private val firstDocument = MutableStateFlow<FirstDocument>(FirstDocument.None)

    /** The first document as it is added, then whether its text is being read. */
    private val firstDocumentState =
        firstDocument.flatMapLatest { added ->
            if (added !is FirstDocument.Added) {
                flowOf(added)
            } else {
                session.documents.observeDoc(added.docId).map { item ->
                    if (item == null) {
                        added
                    } else {
                        added.copy(
                            title = item.doc.displayTitle,
                            text = FirstDocumentText.of(item.doc.ocrState),
                        )
                    }
                }
            }
        }

    private val languages = MutableStateFlow<OcrLanguageChoice?>(null)

    private val documentCount =
        session.documents
            .observeDocuments(LibraryFilter(), LibrarySort())
            .map { it.size }
            .distinctUntilChanged()

    val state: StateFlow<FirstRunState> =
        combine(
            combine(documentCount, session.ingest.scanProgress, ::Pair),
            session.sync.status,
            session.sync.config.map { config -> config.remote?.takeIf { config.configured }?.let(::cloudFolderLabel) },
            firstDocumentState,
            languages,
        ) { (documents, scan), sync, cloudFolder, first, choice ->
            FirstRunState(documents, scan, sync, cloudFolder, first, choice)
        }.stateIn(viewModelScope, WhileSubscribedTimeout, FirstRunState())

    init {
        viewModelScope.launch {
            val setting = settings.settings.first().ocrLanguage
            val selected =
                setting?.let(OcrLanguages::parse)?.ifEmpty { null } ?: OcrLanguages.forAppLanguage(appLanguage)
            languages.value = OcrLanguageChoice(selected)
            val models = readOcrModels()
            // The models a user installed are the languages their papers are in: offered ticked, as
            // long as the app knows them (a full set from the distribution would tick everything).
            val installedKnown =
                models
                    ?.takeIf { setting == null && it.source != OcrModelSource.BUNDLED }
                    ?.installed
                    .orEmpty()
                    .filter { it in OcrLanguages.BUNDLED }
            languages.update { choice ->
                choice?.copy(selected = (choice.selected + installedKnown).distinct(), models = models)
            }
        }
    }

    /** Tick or untick [code]; the last language stays, as text recognition needs one. */
    fun toggleLanguage(code: String) =
        languages.update { choice ->
            when {
                choice == null -> null
                code !in choice.selected -> choice.copy(selected = choice.selected + code)
                choice.selected.size > 1 -> choice.copy(selected = choice.selected - code)
                else -> choice
            }
        }

    /** Ask again which models the device has: the user installed the missing ones meanwhile. */
    fun checkModelsAgain() {
        viewModelScope.launch {
            val models = readOcrModels()
            languages.update { it?.copy(models = models) }
        }
    }

    /**
     * Keep the chosen languages: as the automatic choice when they are the app language's and
     * English, else as they are.
     */
    fun saveLanguages() {
        val selected = languages.value?.selected ?: return
        val automatic = selected.toSet() == OcrLanguages.forAppLanguage(appLanguage).toSet()
        val value = if (automatic) null else OcrLanguages.format(selected)
        launchReporting(Res.string.common_save_setting_failed) { settings.edit { it.copy(ocrLanguage = value) } }
    }

    /**
     * Add the files picked or dropped. The first that goes in is the one first run follows; a batch
     * is counted as the library counts one, and a single file that did not go in says so.
     */
    fun import(sources: List<Pair<ExternalSource, String>>) {
        if (sources.isEmpty()) return
        adding(Res.string.library_import_failed) {
            var tally = ImportTally()
            var first: FirstDocument.Added? = null
            for ((source, name) in sources) {
                val outcome = session.ingest.importPdf(source, name)
                tally += outcome
                first = first ?: outcome.asFirstDocument(name)
            }
            if (sources.size > 1 || first == null) message(tally.asUiText())
            first
        }
    }

    /** What a scan came to: added as the first document, or why not. */
    fun importScan(result: ScanResult) {
        when (result) {
            is ScanResult.Scanned -> {
                adding(Res.string.common_scan_import_failed) {
                    val outcome = session.ingest.importPdf(result.pdf.source, result.pdf.suggestedName)
                    if (outcome is ImportOutcome.Failed) {
                        message(uiText(Res.string.common_scan_import_failed, outcome.reason.text()))
                    }
                    outcome.asFirstDocument(result.pdf.suggestedName)
                }
            }

            ScanResult.Failed -> {
                message(uiText(Res.string.common_scan_capture_failed))
            }

            ScanResult.Cancelled -> {}
        }
    }

    /**
     * Show the first document as being added while [add] runs, then as what it added; one that added
     * nothing, or failed ([failed] says why), leaves the step as it was.
     */
    private fun adding(
        failed: StringResource,
        add: suspend () -> FirstDocument.Added?,
    ) {
        val before = firstDocument.value
        firstDocument.value = FirstDocument.Adding
        viewModelScope.launch {
            var added: FirstDocument.Added? = null
            try {
                reporting(failed) { added = add() }
            } finally {
                firstDocument.value = added ?: before
            }
        }
    }

    /** Where first run leaves the user when they go on from the first document. */
    fun firstDocumentExit(): FirstRunExit {
        val added = state.value.firstDocument as? FirstDocument.Added
        return if (added == null) {
            FirstRunExit.Library
        } else {
            FirstRunExit.Document(added.docId, added.text)
        }
    }
}

/** The first document an import made, or null when it made none. */
private fun ImportOutcome.asFirstDocument(name: String): FirstDocument.Added? =
    when (this) {
        is ImportOutcome.Imported -> {
            FirstDocument.Added(docId, name, FirstDocumentText.READING)
        }

        is ImportOutcome.Duplicate -> {
            FirstDocument.Added(
                existingDocId,
                name,
                FirstDocumentText.READ,
                alreadyThere = true,
            )
        }

        is ImportOutcome.Failed -> {
            null
        }
    }
