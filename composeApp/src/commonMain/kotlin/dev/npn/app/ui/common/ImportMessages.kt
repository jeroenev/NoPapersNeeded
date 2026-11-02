package dev.npn.app.ui.common

import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.ImportFailure
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.ImportTally
import dev.npn.core.app.IngestFacade
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.fs.ExternalSource
import dev.npn.core.ocr.OcrState
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.settings.SettingsSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_duplicate_count
import no_papers_needed.composeapp.generated.resources.common_failed_count
import no_papers_needed.composeapp.generated.resources.common_import_failure_copy
import no_papers_needed.composeapp.generated.resources.common_import_failure_empty
import no_papers_needed.composeapp.generated.resources.common_import_failure_images
import no_papers_needed.composeapp.generated.resources.common_import_failure_superseded
import no_papers_needed.composeapp.generated.resources.common_imported_count
import no_papers_needed.composeapp.generated.resources.common_join_items
import no_papers_needed.composeapp.generated.resources.library_import_reading_text
import no_papers_needed.composeapp.generated.resources.library_search_tip
import kotlin.time.Duration.Companion.minutes

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

/**
 * What the user asked to import: [pdfs] (a file and the name to give it), each its own document, and
 * [images], made into one PDF together, as a share of several photos does on Android.
 */
class ImportRequest(
    val pdfs: List<Pair<ExternalSource, String>>,
    val images: List<ImportImage> = emptyList(),
)

/** An image to import: its file [name], and how to [read] its bytes, null when it cannot be read. */
class ImportImage(
    val name: String,
    val read: suspend () -> ByteArray?,
)

/** What an [ImportRequest] came to: the [tally], and the documents it brought in, for "Show". */
class ImportBatch(
    val tally: ImportTally,
    val imported: List<String>,
)

/**
 * Import every file of [request], one after the other, and say what became of them. An image that
 * cannot be read counts as failed and is left out of the PDF the others make. Like
 * [IngestFacade.importPdf], it throws only when the session closes.
 */
suspend fun IngestFacade.importBatch(request: ImportRequest): ImportBatch {
    var tally = ImportTally()
    val imported = mutableListOf<String>()

    fun count(outcome: ImportOutcome) {
        tally += outcome
        if (outcome is ImportOutcome.Imported) imported += outcome.docId
    }
    for ((source, name) in request.pdfs) count(importPdf(source, name))
    if (request.images.isNotEmpty()) {
        val read = request.images.map { it.read() }
        tally = tally.copy(failed = tally.failed + read.count { it == null })
        val images = read.filterNotNull()
        if (images.isNotEmpty()) count(importImagesAsPdf(images, imagesPdfName(request.images.first().name)))
    }
    return ImportBatch(tally, imported.distinct())
}

/** The name of the PDF images make: the first image's, as a PDF. */
private fun imagesPdfName(firstImage: String): String = firstImage.substringBeforeLast('.').ifBlank { "image" } + ".pdf"

/**
 * The snackbar text for [ImportBatch]: its tally, and that the new documents' text is being read
 * when there are any (an import queues each for text recognition).
 */
fun ImportBatch.asUiText(): UiText =
    if (imported.isEmpty()) tally.asUiText() else uiText(Res.string.library_import_reading_text, tally.asUiText())

/** How long [offerSearchTip] waits for an import's text to be read. */
private val SearchTipWait = 30.minutes

/** Where a document's text recognition is over, one way or the other. */
private val TextSettled = setOf(OcrState.DONE, OcrState.ADOPTED, OcrState.FAILED)

/** Where a document has searchable text. */
private val HasText = setOf(OcrState.DONE, OcrState.ADOPTED)

/**
 * Once the text of [docIds], just imported, has been read, tip the user to search a word from their
 * pages, through [show]: once per device, and never for a user who has searched already. Waits as
 * long as text recognition takes, up to [SearchTipWait] (where it never finishes, say with no engine
 * installed, the wait would otherwise keep its query live all session; a later import tries again);
 * a document that leaves the library meanwhile no longer counts.
 */
suspend fun offerSearchTip(
    settings: SettingsRepository,
    documents: DocumentRepository,
    docIds: List<String>,
    show: (UiText) -> Unit,
) {
    if (docIds.isEmpty() || settings.settings.value.searchTipDone) return
    val withText =
        withTimeoutOrNull(SearchTipWait) {
            documents
                .observeDocuments(LibraryFilter(docIds = docIds), LibrarySort())
                .first { items -> items.all { it.doc.ocrState in TextSettled } }
                .count { it.doc.ocrState in HasText }
        } ?: return
    if (withText == 0) return
    var first = false
    settings.edit {
        if (it.searchTipDone) {
            it
        } else {
            first = true
            it.copy(searchTipShown = true)
        }
    }
    if (first) show(uiText(Res.plurals.library_search_tip, withText, withText))
}

private val SettingsSnapshot.searchTipDone: Boolean get() = searchTipShown || searchTried
