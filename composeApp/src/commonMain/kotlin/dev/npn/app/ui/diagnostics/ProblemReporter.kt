package dev.npn.app.ui.diagnostics

import dev.npn.app.BuildInfo
import dev.npn.app.platform.platformDescription
import dev.npn.app.platform.systemLocaleTag
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.LibrarySession
import dev.npn.core.diagnostics.AiSummary
import dev.npn.core.diagnostics.CloudSyncSummary
import dev.npn.core.diagnostics.Diagnostics
import dev.npn.core.diagnostics.DiagnosticsReport
import dev.npn.core.diagnostics.LibrarySummary
import dev.npn.core.settings.SettingsSnapshot
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_report_problem_failed

/**
 * A "Report a problem" bundle, from the tap that asks for one to the file the UI writes. The
 * bundle is built on demand only; nothing leaves the device until the user saves or shares it.
 */
sealed interface ProblemReportState {
    /** Nothing asked for, or the last bundle was handed to the platform. */
    data object Idle : ProblemReportState

    /** Counting the library and reading the log. */
    data object Building : ProblemReportState

    /** A scrubbed bundle, ready to show and then write, with the issue it prefills. */
    data class Ready(
        val fileName: String,
        val text: String,
        val issueUrl: String,
    ) : ProblemReportState
}

/** What a screen that offers "Report a problem" can do with it. */
interface ProblemReportActions {
    fun reportProblem()

    /** The bundle was handed to the platform (or the user let it go): back to idle. */
    fun problemReportHandled()

    /** Writing or sharing the bundle failed; says why. */
    fun problemReportFailed(failure: Throwable)
}

/** A built bundle: its file name, its scrubbed text and the GitHub issue it prefills. */
class ProblemReport(
    val fileName: String,
    val text: String,
    val issueUrl: String,
)

/**
 * "Report a problem" for a viewmodel: builds the bundle on [compute] when asked, holds where it
 * stands ([state]), and reports a failure through [message].
 */
class ProblemReporter(
    private val scope: CoroutineScope,
    private val compute: CoroutineDispatcher,
    private val message: (UiText) -> Unit,
    private val build: suspend () -> ProblemReport,
) : ProblemReportActions {
    private val current = MutableStateFlow<ProblemReportState>(ProblemReportState.Idle)
    val state: StateFlow<ProblemReportState> = current.asStateFlow()

    override fun reportProblem() {
        if (current.value == ProblemReportState.Building) return
        current.value = ProblemReportState.Building
        scope.launch {
            current.value =
                runCatchingCancellable { withContext(compute) { build() } }.fold(
                    onSuccess = { ProblemReportState.Ready(it.fileName, it.text, it.issueUrl) },
                    onFailure = { failure ->
                        message(uiText(Res.string.common_settings_report_problem_failed, failure.reasonText()))
                        ProblemReportState.Idle
                    },
                )
        }
    }

    override fun problemReportHandled() {
        current.value = ProblemReportState.Idle
    }

    override fun problemReportFailed(failure: Throwable) {
        current.value = ProblemReportState.Idle
        message(uiText(Res.string.common_settings_report_problem_failed, failure.reasonText()))
    }
}

/**
 * Build a bundle from the device's settings ([snapshot]) and, when one is open, the library
 * ([session]). Document content and names never enter: the session reports counts, and only the
 * log is free-form enough to need [Diagnostics] redaction.
 *
 * Each section is collected on its own. A report is often asked for because something is broken,
 * so a section that fails (a library whose journal cannot be listed, say) is left out and logged,
 * and the log, read last, carries why.
 */
suspend fun buildProblemReport(
    snapshot: SettingsSnapshot,
    session: LibrarySession?,
    readLog: suspend (Long) -> String,
    log: NpnLog,
): ProblemReport {
    suspend fun <T> section(
        what: String,
        collect: suspend () -> T,
    ): T? =
        runCatchingCancellable { collect() }
            .onFailure { log.warn("Collecting the $what for a problem report failed", it) }
            .getOrNull()

    val library = session?.let { section("library counts") { librarySummary(it) } }
    val cloudSync = session?.let { section("cloud sync status") { cloudSyncSummary(it) } }
    val remote =
        session?.let {
            section("cloud remote") {
                it.sync.config
                    .first()
                    .remote
            }
        }
    val report =
        DiagnosticsReport(
            appVersion = BuildInfo.VERSION,
            platform = section("platform") { platformDescription() }.orEmpty(),
            locale = snapshot.appLanguage ?: section("locale") { systemLocaleTag() }.orEmpty(),
            library = library,
            cloudSync = cloudSync,
            ai = AiSummary(enabled = snapshot.aiEnabled, provider = snapshot.aiProvider.name, model = snapshot.aiModel),
            logTail = section("log") { readLog(Diagnostics.MAX_LOG_BYTES) }.orEmpty(),
        )
    return ProblemReport(
        fileName = Diagnostics.bundleFileName(report.appVersion),
        text = Diagnostics.render(report, secrets = reportSecrets(snapshot, remote)),
        issueUrl = Diagnostics.issueUrl(report),
    )
}

private suspend fun librarySummary(session: LibrarySession): LibrarySummary {
    val counts = session.maintenance.diagnostics()
    val mode = session.reviewConfig.reviewMode.first()
    return LibrarySummary(
        liveDocuments = counts.liveDocuments,
        trashedDocuments = counts.trashedDocuments,
        missingDocuments = counts.missingDocuments,
        tags = counts.tags,
        fields = counts.fields,
        journalObjects = counts.journalObjects,
        ocrQueue = counts.ocrQueue,
        enrichmentQueue = counts.enrichmentQueue,
        editRuns = counts.editRuns,
        reviewQueue = session.documents.observeReviewQueueCount(mode).first(),
    )
}

private suspend fun cloudSyncSummary(session: LibrarySession): CloudSyncSummary {
    val status = session.sync.status.first()
    val config = session.sync.config.first()
    return CloudSyncSummary(
        configured = config.configured,
        automatic = config.enabled,
        intervalMinutes = config.intervalMinutes,
        running = status.running,
        lastRunSucceeded = status.lastRun?.ok,
        lastRunFailure = status.lastRun?.failure?.name,
        lastRunConflicts =
            status.lastRun
                ?.report
                ?.conflicts
                ?.size ?: 0,
        lastRunErrors =
            status.lastRun
                ?.report
                ?.errors
                ?.size ?: 0,
    )
}

/** Free-form log text may quote these; scrubbing them by value is stronger than any pattern. */
private fun reportSecrets(
    snapshot: SettingsSnapshot,
    remote: String?,
): List<String> =
    listOfNotNull(
        snapshot.libraryLocation,
        snapshot.watchedFolder,
        snapshot.installationId,
        snapshot.aiApiKey,
        snapshot.aiBaseUrl,
        remote,
    )
