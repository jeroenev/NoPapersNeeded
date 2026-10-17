package dev.npn.app.ui.common

import dev.npn.core.app.ImportFailure
import dev.npn.core.app.ImportTally
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_duplicate_count
import no_papers_needed.composeapp.generated.resources.common_failed_count
import no_papers_needed.composeapp.generated.resources.common_import_failure_copy
import no_papers_needed.composeapp.generated.resources.common_import_failure_empty
import no_papers_needed.composeapp.generated.resources.common_import_failure_images
import no_papers_needed.composeapp.generated.resources.common_import_failure_superseded
import no_papers_needed.composeapp.generated.resources.common_imported_count
import no_papers_needed.composeapp.generated.resources.common_join_items

/**
 * An import batch's tally as the snackbar reports it: "3 imported, 1 duplicate", leaving out what is
 * 0. A batch that came to nothing at all reads "0 imported".
 */
fun ImportTally.asUiText(): UiText =
    listOfNotNull(
        imported.takeIf { it > 0 || total == 0 }?.let { uiText(Res.plurals.common_imported_count, it, it) },
        duplicates.takeIf { it > 0 }?.let { uiText(Res.plurals.common_duplicate_count, it, it) },
        failed.takeIf { it > 0 }?.let { uiText(Res.plurals.common_failed_count, it, it) },
    ).reduce { summary, part -> uiText(Res.string.common_join_items, summary, part) }

/** Why an import added nothing, as the reason clause of a "… failed: %1$s" message. */
fun ImportFailure.text(): UiText =
    when (this) {
        ImportFailure.EMPTY_FILE -> uiText(Res.string.common_import_failure_empty)
        ImportFailure.UNREADABLE_IMAGES -> uiText(Res.string.common_import_failure_images)
        ImportFailure.COPY_FAILED -> uiText(Res.string.common_import_failure_copy)
        ImportFailure.SUPERSEDED -> uiText(Res.string.common_import_failure_superseded)
    }
