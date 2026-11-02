@file:OptIn(ExperimentalLayoutApi::class)

package dev.npn.app.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.platform.LocalDocumentScanner
import dev.npn.app.platform.OcrModelSource
import dev.npn.app.platform.OcrModels
import dev.npn.app.platform.ScanResult
import dev.npn.app.platform.acceptsFileDrops
import dev.npn.app.platform.installCommand
import dev.npn.app.platform.ocrHasLanguageSetting
import dev.npn.app.platform.platformFileToExternalSource
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.LocalAppLocale
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TestTags
import dev.npn.app.ui.common.ocrLanguageName
import dev.npn.app.ui.common.resolve
import dev.npn.app.ui.library.bannerText
import dev.npn.app.ui.settings.failureText
import dev.npn.app.ui.settings.progressLabel
import dev.npn.core.app.LibrarySession
import dev.npn.core.fs.ExternalSource
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.sync.SyncFailure
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.onboarding_connecting_background
import no_papers_needed.composeapp.generated.resources.onboarding_connecting_cloud
import no_papers_needed.composeapp.generated.resources.onboarding_connecting_count
import no_papers_needed.composeapp.generated.resources.onboarding_connecting_done
import no_papers_needed.composeapp.generated.resources.onboarding_connecting_empty
import no_papers_needed.composeapp.generated.resources.onboarding_connecting_title
import no_papers_needed.composeapp.generated.resources.onboarding_connecting_waiting
import no_papers_needed.composeapp.generated.resources.onboarding_continue
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_adding
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_already
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_choose
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_drop
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_existing
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_help
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_open
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_read
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_reading
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_reading_help
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_scan
import no_papers_needed.composeapp.generated.resources.onboarding_first_document_title
import no_papers_needed.composeapp.generated.resources.onboarding_go_to_library
import no_papers_needed.composeapp.generated.resources.onboarding_languages_bundled
import no_papers_needed.composeapp.generated.resources.onboarding_languages_check_again
import no_papers_needed.composeapp.generated.resources.onboarding_languages_checking
import no_papers_needed.composeapp.generated.resources.onboarding_languages_help
import no_papers_needed.composeapp.generated.resources.onboarding_languages_install_with
import no_papers_needed.composeapp.generated.resources.onboarding_languages_missing
import no_papers_needed.composeapp.generated.resources.onboarding_languages_missing_manual
import no_papers_needed.composeapp.generated.resources.onboarding_languages_no_tesseract
import no_papers_needed.composeapp.generated.resources.onboarding_languages_title
import no_papers_needed.composeapp.generated.resources.onboarding_skip
import no_papers_needed.composeapp.generated.resources.onboarding_watch_folder
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * First run once its [session]'s library is open: the [step] of [path]'s [steps] the user is on
 * ([onStep] moves on to another), and where they go when it is over ([onFinish]). [readOcrModels]
 * asks which text recognition models the device has. Failures of what the steps do show on the
 * app's [snackbar]. While the first document is asked for, files dropped on the window go to it
 * ([onDropTarget] hands over the handler, and null once the step is gone).
 */
@Composable
internal fun FirstRunScreen(
    session: LibrarySession,
    settings: SettingsRepository,
    readOcrModels: suspend () -> OcrModels?,
    path: FirstRunPath,
    step: Int,
    onStep: (Int) -> Unit,
    onFinish: (FirstRunExit) -> Unit,
    snackbar: SnackbarHostState,
    onDropTarget: (((List<Pair<ExternalSource, String>>) -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
    appLanguage: String = LocalAppLocale.current,
    vm: FirstRunViewModel = viewModel { FirstRunViewModel(session, settings, appLanguage, readOcrModels) },
) {
    SnackbarMessages(vm.messages, snackbar)
    val state by vm.state.collectAsStateWithLifecycle()
    val steps = path.steps(askLanguages = ocrHasLanguageSetting())
    val current = steps.getOrElse(step) { steps.last() }
    val next = { if (step + 1 < steps.size) onStep(step + 1) else onFinish(FirstRunExit.Library) }
    CenteredPane(modifier) {
        when (current) {
            FirstRunStep.LANGUAGES -> {
                LanguagesStep(
                    choice = state.languages,
                    onToggle = vm::toggleLanguage,
                    onCheckAgain = vm::checkModelsAgain,
                    onContinue = {
                        vm.saveLanguages()
                        next()
                    },
                )
            }

            FirstRunStep.FIRST_DOCUMENT -> {
                val currentOnDropTarget by rememberUpdatedState(onDropTarget)
                DisposableEffect(vm) {
                    currentOnDropTarget(vm::import)
                    onDispose { currentOnDropTarget(null) }
                }
                FirstDocumentStep(
                    state = state,
                    onImport = vm::import,
                    onScanResult = vm::importScan,
                    onOpen = { onFinish(vm.firstDocumentExit()) },
                    onWatchFolder = { onFinish(FirstRunExit.WatchFolder) },
                    onSkip = { onFinish(FirstRunExit.Library) },
                )
            }

            FirstRunStep.CONNECTING -> {
                ConnectingStep(state, onDone = { onFinish(FirstRunExit.Library) })
            }
        }
    }
}

/**
 * Which languages text recognition reads: the ones offered, ticked as [choice] has them, and what
 * this device still needs for them: Tesseract, or a model, with the command that installs it where
 * the app knows one, or that the app ships them itself.
 */
@Composable
private fun ColumnScope.LanguagesStep(
    choice: OcrLanguageChoice?,
    onToggle: (String) -> Unit,
    onCheckAgain: () -> Unit,
    onContinue: () -> Unit,
) {
    StepTitle(stringResource(Res.string.onboarding_languages_title))
    HelpText(stringResource(Res.string.onboarding_languages_help), textAlign = TextAlign.Center)
    if (choice == null) {
        CircularProgressIndicator()
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        choice.offered.forEach { code ->
            val selected = code in choice.selected
            FilterChip(
                selected = selected,
                onClick = { onToggle(code) },
                label = { Text(text = ocrLanguageName(code)) },
                leadingIcon =
                    if (selected) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
    ModelsNote(choice, onCheckAgain)
    Button(onClick = onContinue, modifier = Modifier.testTag(TestTags.FIRST_RUN_CONTINUE)) {
        Text(stringResource(Res.string.onboarding_continue))
    }
}

/** What the chosen languages still need on this device, if anything. */
@Composable
private fun ColumnScope.ModelsNote(
    choice: OcrLanguageChoice,
    onCheckAgain: () -> Unit,
) {
    val models = choice.models
    if (models == null) {
        HelpText(stringResource(Res.string.onboarding_languages_checking))
        return
    }
    val missing = choice.missing
    if (models.source == OcrModelSource.BUNDLED) {
        // The app's own package has every model it offers.
        HelpText(stringResource(Res.string.onboarding_languages_bundled), textAlign = TextAlign.Center)
        return
    }
    if (missing.isEmpty()) return
    val names = missing.map { ocrLanguageName(it) }.joinToString(", ")
    Text(
        text =
            if (choice.tesseractMissing) {
                stringResource(Res.string.onboarding_languages_no_tesseract)
            } else {
                stringResource(Res.string.onboarding_languages_missing, names)
            },
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    val command = models.source.installCommand(missing, withTesseract = choice.tesseractMissing)
    if (command != null) {
        HelpText(stringResource(Res.string.onboarding_languages_install_with))
        SelectionContainer {
            Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        HelpText(stringResource(Res.string.onboarding_languages_missing_manual, names), textAlign = TextAlign.Center)
    }
    TextButton(onClick = onCheckAgain) { Text(stringResource(Res.string.onboarding_languages_check_again)) }
}

/**
 * Add the first document: scan it (where the platform scans), choose files, or drop them on the
 * window (desktop, which shows where); or watch a folder instead, or skip. Once it is in, the
 * step says so, and whether its text is still being read, and opens it.
 */
@Composable
private fun ColumnScope.FirstDocumentStep(
    state: FirstRunState,
    onImport: (List<Pair<ExternalSource, String>>) -> Unit,
    onScanResult: (ScanResult) -> Unit,
    onOpen: () -> Unit,
    onWatchFolder: () -> Unit,
    onSkip: () -> Unit,
) {
    val picker =
        rememberFilePickerLauncher(type = FileKitType.File("pdf"), mode = FileKitMode.Multiple()) { files ->
            if (!files.isNullOrEmpty()) onImport(files.map { platformFileToExternalSource(it) to it.name })
        }
    // Null without a scanner (desktop); a result comes back here even to a recreated activity.
    val scan = LocalDocumentScanner.current?.rememberLauncher(onScanResult)
    StepTitle(stringResource(Res.string.onboarding_first_document_title))
    when (val document = state.firstDocument) {
        is FirstDocument.Added -> {
            AddedDocument(document, onOpen)
            return
        }

        FirstDocument.Adding -> {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(Res.string.onboarding_first_document_adding))
            }
        }

        FirstDocument.None -> {
            HelpText(stringResource(Res.string.onboarding_first_document_help), textAlign = TextAlign.Center)
        }
    }
    val existing = state.documents ?: 0
    if (existing > 0) {
        HelpText(
            pluralStringResource(Res.plurals.onboarding_first_document_existing, existing, existing),
            textAlign = TextAlign.Center,
        )
    }
    val adding = state.firstDocument == FirstDocument.Adding
    if (scan != null) {
        Button(onClick = scan, enabled = !adding) {
            Icon(Icons.Outlined.DocumentScanner, contentDescription = null)
            Text(stringResource(Res.string.onboarding_first_document_scan), Modifier.padding(start = 8.dp))
        }
    }
    val choose = @Composable {
        Icon(Icons.Outlined.FileUpload, contentDescription = null)
        Text(stringResource(Res.string.onboarding_first_document_choose), Modifier.padding(start = 8.dp))
    }
    if (scan == null) {
        Button(onClick = { picker.launch() }, enabled = !adding) { choose() }
    } else {
        OutlinedButton(onClick = { picker.launch() }, enabled = !adding) { choose() }
    }
    if (acceptsFileDrops()) DropZone()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onWatchFolder) { Text(stringResource(Res.string.onboarding_watch_folder)) }
        TextButton(onClick = onSkip, modifier = Modifier.testTag(TestTags.FIRST_RUN_SKIP)) {
            Text(
                stringResource(
                    if (existing > 0) Res.string.onboarding_go_to_library else Res.string.onboarding_skip,
                ),
            )
        }
    }
}

/**
 * Where files dropped on the window go, shown as such. The window takes the drop wherever it lands
 * (see [dev.npn.app.ui.common.DesktopBridge]); this only makes it visible.
 */
@Composable
private fun DropZone() {
    Box(
        Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                RoundedCornerShape(12.dp),
            ).padding(vertical = 32.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(Res.string.onboarding_first_document_drop),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The first document is in: which, whether its text is being read, and the way to it. */
@Composable
private fun ColumnScope.AddedDocument(
    document: FirstDocument.Added,
    onOpen: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(document.title, style = MaterialTheme.typography.titleMedium)
    }
    when {
        document.alreadyThere -> {
            HelpText(stringResource(Res.string.onboarding_first_document_already), textAlign = TextAlign.Center)
        }

        document.readingText -> {
            Text(stringResource(Res.string.onboarding_first_document_reading))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            HelpText(stringResource(Res.string.onboarding_first_document_reading_help), textAlign = TextAlign.Center)
        }

        else -> {
            Text(stringResource(Res.string.onboarding_first_document_read), textAlign = TextAlign.Center)
        }
    }
    Button(onClick = onOpen, modifier = Modifier.testTag(TestTags.FIRST_RUN_CONTINUE)) {
        Text(stringResource(Res.string.onboarding_first_document_open))
    }
}

/**
 * A library another device uses, coming in: the rescan reading its folder and the cloud's first
 * pass, in plain words, and how many documents are here so far. The user may go on at any time;
 * it carries on in the background.
 */
@Composable
private fun ColumnScope.ConnectingStep(
    state: FirstRunState,
    onDone: () -> Unit,
) {
    StepTitle(stringResource(Res.string.onboarding_connecting_title))
    state.cloudFolder?.let { folder ->
        HelpText(stringResource(Res.string.onboarding_connecting_cloud, folder), textAlign = TextAlign.Center)
    }
    val sync = state.sync
    val scan = state.scan
    val syncProgress = sync.progress
    // A cloud library brings its documents in with its first pass, which starts once it is open.
    val awaitingFirstPass = state.cloudFolder != null && sync.lastRun == null
    val working = scan != null || sync.running || awaitingFirstPass
    val failedRun = sync.lastRun?.takeIf { !it.ok }
    when {
        sync.running && syncProgress != null -> {
            Text(progressLabel(syncProgress), textAlign = TextAlign.Center)
            Progress(syncProgress.done, syncProgress.total)
        }

        scan != null -> {
            Text(scan.bannerText().resolve(), textAlign = TextAlign.Center)
            Progress(scan.hashed, scan.total)
        }

        working -> {
            Text(stringResource(Res.string.onboarding_connecting_waiting), textAlign = TextAlign.Center)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        failedRun != null -> {
            Text(
                failureText(failedRun.failure ?: SyncFailure.UNKNOWN, failedRun.failureDetail),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
    val documents = state.documents
    if (documents != null) {
        val text =
            when {
                working -> pluralStringResource(Res.plurals.onboarding_connecting_count, documents, documents)
                documents == 0 -> stringResource(Res.string.onboarding_connecting_empty)
                else -> pluralStringResource(Res.plurals.onboarding_connecting_done, documents, documents)
            }
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
    if (working) HelpText(stringResource(Res.string.onboarding_connecting_background), textAlign = TextAlign.Center)
    Button(onClick = onDone, modifier = Modifier.testTag(TestTags.FIRST_RUN_CONTINUE)) {
        Text(stringResource(Res.string.onboarding_go_to_library))
    }
}

/** [done] of [total], or an indeterminate bar while the total is unknown. */
@Composable
private fun ColumnScope.Progress(
    done: Int,
    total: Int,
) {
    if (total > 0) {
        LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
