package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.platform.isAppPrivateLocation
import dev.npn.app.platform.supportsSafStorage
import dev.npn.app.ui.common.ExpanderRow
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.common.TypedTextField
import dev.npn.app.ui.common.rememberStorageAccess
import dev.npn.app.ui.common.resolve
import dev.npn.core.app.LibraryVerificationNote
import dev.npn.core.app.LibraryVerificationProblem
import dev.npn.core.app.LibraryVerificationProgress
import dev.npn.core.app.LibraryVerificationReport
import dev.npn.core.app.VerificationNoteKind
import dev.npn.core.app.VerificationPhase
import dev.npn.core.app.VerificationProblemKind
import dev.npn.core.model.FieldType
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathExpansion
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.settings.LibraryLocations
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_advanced
import no_papers_needed.composeapp.generated.resources.common_settings_change_library
import no_papers_needed.composeapp.generated.resources.common_settings_clear_thumbnail_cache
import no_papers_needed.composeapp.generated.resources.common_settings_library_saf_note
import no_papers_needed.composeapp.generated.resources.common_settings_no_library
import no_papers_needed.composeapp.generated.resources.common_settings_storage
import no_papers_needed.composeapp.generated.resources.common_settings_storage_access_missing
import no_papers_needed.composeapp.generated.resources.common_settings_storage_organize_help
import no_papers_needed.composeapp.generated.resources.common_settings_storage_organize_paths
import no_papers_needed.composeapp.generated.resources.common_settings_storage_path_template
import no_papers_needed.composeapp.generated.resources.common_settings_storage_preview
import no_papers_needed.composeapp.generated.resources.common_settings_storage_sample_correspondent
import no_papers_needed.composeapp.generated.resources.common_settings_storage_sample_tag_home
import no_papers_needed.composeapp.generated.resources.common_settings_storage_sample_tag_utilities
import no_papers_needed.composeapp.generated.resources.common_settings_storage_sample_title
import no_papers_needed.composeapp.generated.resources.common_settings_storage_sample_type
import no_papers_needed.composeapp.generated.resources.common_settings_storage_variables
import no_papers_needed.composeapp.generated.resources.common_settings_syncs_across_devices
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnail_cache_help
import no_papers_needed.composeapp.generated.resources.common_settings_use_saf
import no_papers_needed.composeapp.generated.resources.common_settings_use_saf_help
import no_papers_needed.composeapp.generated.resources.common_settings_verify_cancel
import no_papers_needed.composeapp.generated.resources.common_settings_verify_checking_documents
import no_papers_needed.composeapp.generated.resources.common_settings_verify_checking_journal
import no_papers_needed.composeapp.generated.resources.common_settings_verify_clean
import no_papers_needed.composeapp.generated.resources.common_settings_verify_close
import no_papers_needed.composeapp.generated.resources.common_settings_verify_copied
import no_papers_needed.composeapp.generated.resources.common_settings_verify_copy
import no_papers_needed.composeapp.generated.resources.common_settings_verify_count_documents
import no_papers_needed.composeapp.generated.resources.common_settings_verify_count_journal
import no_papers_needed.composeapp.generated.resources.common_settings_verify_count_problems
import no_papers_needed.composeapp.generated.resources.common_settings_verify_group_documents
import no_papers_needed.composeapp.generated.resources.common_settings_verify_group_journal
import no_papers_needed.composeapp.generated.resources.common_settings_verify_hash
import no_papers_needed.composeapp.generated.resources.common_settings_verify_help
import no_papers_needed.composeapp.generated.resources.common_settings_verify_library
import no_papers_needed.composeapp.generated.resources.common_settings_verify_missing
import no_papers_needed.composeapp.generated.resources.common_settings_verify_more
import no_papers_needed.composeapp.generated.resources.common_settings_verify_not_a_file
import no_papers_needed.composeapp.generated.resources.common_settings_verify_note_superseded
import no_papers_needed.composeapp.generated.resources.common_settings_verify_note_unsupported
import no_papers_needed.composeapp.generated.resources.common_settings_verify_notes
import no_papers_needed.composeapp.generated.resources.common_settings_verify_orphaned
import no_papers_needed.composeapp.generated.resources.common_settings_verify_problem_line
import no_papers_needed.composeapp.generated.resources.common_settings_verify_report_title
import no_papers_needed.composeapp.generated.resources.common_settings_verify_size
import no_papers_needed.composeapp.generated.resources.common_settings_verify_store_unreadable
import no_papers_needed.composeapp.generated.resources.common_settings_verify_title
import no_papers_needed.composeapp.generated.resources.common_settings_verify_unreadable
import no_papers_needed.composeapp.generated.resources.common_storage_access_grant
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Variables accepted by the storage-path template, echoed in the field's help copy. */
private const val STORAGE_PATH_VARIABLES =
    "{title}, {doc_date}, {doc_date:yyyy-MM}, {docType}, {correspondent}, {tags}, {field:key}, {field:key:yyyy}"

/**
 * Where documents live: which folder is the library and how PDFs are laid out inside it. Behind
 * Advanced: the derived thumbnail cache that can be thrown away at any time, and on Android the
 * Storage Access Framework switch.
 */
@Composable
internal fun LibrarySettingsPane(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    val location = state.settings.snapshot.libraryLocation
    val safLibrary = location != null && LibraryLocations.isContentUri(location)
    // A direct library on shared storage, which Android's all-files grant governs; a cloud
    // library's working copy is in the app's own storage, which no grant governs.
    val needsGrant = location != null && !safLibrary && !isAppPrivateLocation(location)
    val storage = rememberStorageAccess()
    SettingsSection(title = null) {
        Text(
            text = location ?: stringResource(Res.string.common_settings_no_library),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (safLibrary) {
            HelpText(stringResource(Res.string.common_settings_library_saf_note))
        }
        // A direct library whose grant was revoked still opens what the app itself wrote, but not
        // what Syncthing wrote next to it: say so where the user looks for the library.
        if (needsGrant && !storage.usable) {
            HelpText(stringResource(Res.string.common_settings_storage_access_missing))
            SettingsActionRow(
                label = stringResource(Res.string.common_storage_access_grant),
                onClick = storage.request,
            )
        }
        SettingsActionRow(
            label = stringResource(Res.string.common_settings_change_library),
            onClick = actions::changeLibrary,
        )
        AddDeviceAction(state)
        VerifyLibraryAction(state.verification, actions)
    }

    SettingsSection(title = stringResource(Res.string.common_settings_storage)) {
        StoragePathRuleEditor(
            template = state.settings.storagePathTemplate,
            enabled = state.settings.storagePathEnabled,
            fieldTypes = state.fields.associate { it.key to it.type },
            onTemplateChange = actions::setStoragePathTemplate,
            onEnabledChange = actions::setStoragePathEnabled,
        )
    }

    // What most people never need, behind a disclosure a settings search opens when it finds one.
    SettingsSection(title = null) {
        var advanced by rememberAdvancedExpanded()
        ExpanderRow(advanced, onToggle = { advanced = !advanced }) {
            Text(stringResource(Res.string.common_settings_advanced), Modifier.weight(1f))
        }
        if (advanced) {
            SettingsActionRow(
                label = stringResource(Res.string.common_settings_clear_thumbnail_cache),
                onClick = actions::clearThumbnailCache,
                help = stringResource(Res.string.common_settings_thumbnail_cache_help),
            )
            // Android only: the Storage Access Framework stays available for folders that have no
            // path (cloud document providers). It changes what the next folder choice persists,
            // nothing else, so a library already opened through it keeps working however it is set;
            // the folder picker's "not on device storage" message points here.
            if (supportsSafStorage()) {
                SwitchRow(
                    label = stringResource(Res.string.common_settings_use_saf),
                    help = stringResource(Res.string.common_settings_use_saf_help),
                    checked = state.settings.snapshot.useSafStorage,
                    onCheckedChange = { on -> actions.edit { it.copy(useSafStorage = on) } },
                )
            }
        }
    }
}

/**
 * Toggle plus template for automatic path organisation. Switching the rule on is gated on the
 * template parsing cleanly, so an invalid template can never be armed; switching it off never is,
 * so a rule whose template was edited into an invalid one can still be stopped. The preview renders
 * the template against a sample document so the effect is visible before it applies to real files.
 */
@Composable
internal fun StoragePathRuleEditor(
    template: String,
    enabled: Boolean,
    /** The library's field types: a date pattern is valid on any field the registry calls a date. */
    fieldTypes: Map<String, FieldType>,
    onTemplateChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    // Rendered against a fictional document in the app's language.
    val sample =
        StoragePathContext.sample(
            fieldTypes = fieldTypes,
            title = stringResource(Res.string.common_settings_storage_sample_title),
            docType = stringResource(Res.string.common_settings_storage_sample_type),
            correspondent = stringResource(Res.string.common_settings_storage_sample_correspondent),
            tags =
                listOf(
                    stringResource(Res.string.common_settings_storage_sample_tag_home),
                    stringResource(Res.string.common_settings_storage_sample_tag_utilities),
                ),
        )
    val expansion = StoragePathTemplate.expand(template, sample)
    val error = (expansion as? StoragePathExpansion.Invalid)?.error
    SwitchRow(
        label = stringResource(Res.string.common_settings_storage_organize_paths),
        help = stringResource(Res.string.common_settings_storage_organize_help),
        checked = enabled,
        enabled = enabled || error == null,
        onCheckedChange = onEnabledChange,
    )
    TypedTextField(
        value = template,
        onValueChange = onTemplateChange,
        onDone = {},
        label = stringResource(Res.string.common_settings_storage_path_template),
        supportingText = {
            Text(
                text =
                    when (expansion) {
                        is StoragePathExpansion.Path -> {
                            stringResource(Res.string.common_settings_storage_preview, expansion.path)
                        }

                        is StoragePathExpansion.Invalid -> {
                            expansion.error.text().resolve()
                        }
                    },
            )
        },
        isError = error != null,
        modifier = Modifier.fillMaxWidth(),
    )
    HelpText(stringResource(Res.string.common_settings_storage_variables, STORAGE_PATH_VARIABLES))
    HelpText(stringResource(Res.string.common_settings_syncs_across_devices))
}

/**
 * "Verify library": a read-only check with a progress state while it walks, and the result dialog
 * once it is done. It never runs on its own; the user asks for it where the library's files live.
 */
@Composable
private fun VerifyLibraryAction(
    verification: LibraryVerificationState,
    actions: SettingsPaneActions,
) {
    if (verification.running) {
        val progress = verification.progress
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (progress != null) {
                Text(
                    text = verificationProgressText(progress),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            OutlinedButton(onClick = actions::cancelLibraryVerification) {
                Text(text = stringResource(Res.string.common_settings_verify_cancel))
            }
        }
        return
    }
    SettingsActionRow(
        label = stringResource(Res.string.common_settings_verify_library),
        onClick = actions::verifyLibrary,
        help = stringResource(Res.string.common_settings_verify_help),
    )
    verification.report?.let { report ->
        VerifyLibraryReportDialog(
            report = report,
            copied = verification.reportCopied,
            onCopy = actions::copyLibraryVerificationReport,
            onDismiss = actions::dismissLibraryVerification,
        )
    }
}

/**
 * The result of a check: the counts, the problems grouped by where they are, and the expected
 * leftovers. [onCopy] gets the same report as plain text, built in the app's language.
 */
@Composable
private fun VerifyLibraryReportDialog(
    report: LibraryVerificationReport,
    copied: Boolean,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val text = verificationReportText(report)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(Res.string.common_settings_verify_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (line in verificationSummaryLines(report)) {
                    Text(text = line, style = MaterialTheme.typography.bodyMedium)
                }
                // A store that could not be listed belongs to neither group; its sentence already
                // says which part of the library it is about.
                for (problem in report.storeProblems) {
                    Text(text = verificationProblemText(problem), style = MaterialTheme.typography.bodySmall)
                }
                val documents = report.documentProblems
                if (documents.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.common_settings_verify_group_documents),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    for (problem in documents.take(VERIFY_LISTED_PROBLEMS)) {
                        Text(text = verificationProblemLine(problem), style = MaterialTheme.typography.bodySmall)
                    }
                    VerificationMoreLine(documents.size)
                }
                val objects = report.objectProblems
                if (objects.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.common_settings_verify_group_journal),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    for (problem in objects.take(VERIFY_LISTED_PROBLEMS)) {
                        Text(text = verificationProblemLine(problem), style = MaterialTheme.typography.bodySmall)
                    }
                    VerificationMoreLine(objects.size)
                }
                val superseded = report.notes.count { it.kind == VerificationNoteKind.SUPERSEDED_DOCUMENT }
                if (superseded > 0) {
                    HelpText(
                        pluralStringResource(
                            Res.plurals.common_settings_verify_notes,
                            superseded,
                            superseded,
                        ),
                    )
                }
                for (note in report.notes.filter { it.kind != VerificationNoteKind.SUPERSEDED_DOCUMENT }) {
                    Text(text = verificationNoteLine(note), style = MaterialTheme.typography.bodySmall)
                }
                if (copied) {
                    HelpText(stringResource(Res.string.common_settings_verify_copied))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy(text) }) {
                Text(text = stringResource(Res.string.common_settings_verify_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(Res.string.common_settings_verify_close))
            }
        },
    )
}

/** The counts, each a plural of its own so a one-item library reads correctly in every language. */
@Composable
private fun verificationSummaryLines(report: LibraryVerificationReport): List<String> =
    buildList {
        add(
            pluralStringResource(
                Res.plurals.common_settings_verify_count_documents,
                report.documentsChecked,
                report.documentsChecked,
            ),
        )
        add(
            pluralStringResource(
                Res.plurals.common_settings_verify_count_journal,
                report.objectsChecked,
                report.objectsChecked,
            ),
        )
        if (report.ok) {
            add(stringResource(Res.string.common_settings_verify_clean))
        } else {
            add(
                pluralStringResource(
                    Res.plurals.common_settings_verify_count_problems,
                    report.problems.size,
                    report.problems.size,
                ),
            )
        }
    }

/** What the walk is doing, named by its phase so the count restarting is not read as a loop. */
@Composable
private fun verificationProgressText(progress: LibraryVerificationProgress): String =
    when (progress.phase) {
        VerificationPhase.DOCUMENTS -> {
            stringResource(
                Res.string.common_settings_verify_checking_documents,
                progress.done,
                progress.total,
            )
        }

        VerificationPhase.OBJECTS -> {
            stringResource(
                Res.string.common_settings_verify_checking_journal,
                progress.done,
                progress.total,
            )
        }
    }

/** One finding: the file it names, then what is wrong with it. */
@Composable
private fun verificationProblemLine(problem: LibraryVerificationProblem): String =
    stringResource(
        Res.string.common_settings_verify_problem_line,
        problem.fileName ?: problem.relPath,
        verificationProblemText(problem),
    )

/** A note in the app's language; notes are expected leftovers, never a problem. */
@Composable
private fun verificationNoteLine(note: LibraryVerificationNote): String =
    when (note.kind) {
        VerificationNoteKind.SUPERSEDED_DOCUMENT -> {
            stringResource(Res.string.common_settings_verify_note_superseded, note.relPath)
        }

        VerificationNoteKind.UNSUPPORTED_OBJECT_STORE -> {
            stringResource(Res.string.common_settings_verify_note_unsupported, note.relPath)
        }
    }

@Composable
private fun verificationProblemText(problem: LibraryVerificationProblem): String =
    when (problem.kind) {
        VerificationProblemKind.DOCUMENT_MISSING,
        VerificationProblemKind.OBJECT_MISSING,
        -> {
            stringResource(Res.string.common_settings_verify_missing)
        }

        VerificationProblemKind.DOCUMENT_UNREADABLE,
        VerificationProblemKind.OBJECT_UNREADABLE,
        -> {
            stringResource(Res.string.common_settings_verify_unreadable)
        }

        VerificationProblemKind.DOCUMENT_NOT_A_FILE -> {
            stringResource(Res.string.common_settings_verify_not_a_file)
        }

        VerificationProblemKind.DOCUMENT_SIZE_MISMATCH -> {
            stringResource(
                Res.string.common_settings_verify_size,
                problem.expectedSize ?: 0L,
                problem.actualSize ?: 0L,
            )
        }

        VerificationProblemKind.DOCUMENT_HASH_MISMATCH,
        VerificationProblemKind.OBJECT_HASH_MISMATCH,
        -> {
            stringResource(Res.string.common_settings_verify_hash)
        }

        VerificationProblemKind.OBJECT_ORPHANED -> {
            stringResource(Res.string.common_settings_verify_orphaned)
        }

        VerificationProblemKind.STORE_UNREADABLE -> {
            stringResource(Res.string.common_settings_verify_store_unreadable)
        }
    }

@Composable
private fun VerificationMoreLine(total: Int) {
    val rest = total - VERIFY_LISTED_PROBLEMS
    if (rest > 0) {
        Text(
            text = stringResource(Res.string.common_settings_verify_more, rest),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The report as plain text for the clipboard: the same sentences the dialog shows, so a copied
 * report reads in the app's language and names the same files.
 */
@Composable
private fun verificationReportText(report: LibraryVerificationReport): String {
    val title = stringResource(Res.string.common_settings_verify_report_title)
    val summary = verificationSummaryLines(report)
    val stores = report.storeProblems.map { verificationProblemText(it) }
    val documents = report.documentProblems.map { " - " + verificationProblemLine(it) }
    val objects = report.objectProblems.map { " - " + verificationProblemLine(it) }
    val notes = report.notes.map { " - " + verificationNoteLine(it) }
    return buildString {
        appendLine(title)
        summary.forEach(::appendLine)
        stores.forEach(::appendLine)
        if (documents.isNotEmpty()) {
            appendLine(stringResource(Res.string.common_settings_verify_group_documents))
            documents.forEach(::appendLine)
        }
        if (objects.isNotEmpty()) {
            appendLine(stringResource(Res.string.common_settings_verify_group_journal))
            objects.forEach(::appendLine)
        }
        notes.forEach(::appendLine)
    }.trimEnd()
}

/** How many findings of one group the dialog lists before summarizing the rest. */
private const val VERIFY_LISTED_PROBLEMS = 50
