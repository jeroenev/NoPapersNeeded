package dev.npn.app.ui.library

import androidx.compose.runtime.Immutable
import dev.npn.core.app.DocListItem

/**
 * One Inbox triage session: the result set it started on, the document it shows now, and the
 * documents it has marked reviewed since. The order and the unreviewed set are a snapshot of the
 * current filter and sort, so the progress line and next/previous keep their footing while a
 * document marked reviewed leaves the live results; each document itself is read live from its own
 * viewmodel as the mode shows it.
 */
@Immutable
data class TriageState(
    val currentId: String,
    /** The documents in the order the library showed them when the mode opened. */
    val order: List<String>,
    /** The documents' rows when the mode opened, seeding a document that leaves the live results. */
    val known: Map<String, DocListItem>,
    /** The documents that were unreviewed when the mode opened. */
    val unreviewed: Set<String>,
    /** The documents marked reviewed in this mode, subtracted from [unreviewed] for the progress line. */
    val reviewed: Set<String> = emptySet(),
) {
    /** 1-based position of [currentId], or 0 when it is no longer in the order. */
    val position: Int get() = order.indexOf(currentId) + 1

    val total: Int get() = order.size

    /** Unreviewed documents left in this session, including the one on screen. */
    val unreviewedRemaining: Int get() = (unreviewed - reviewed).size

    val hasNext: Boolean get() = position in 1 until total

    val hasPrevious: Boolean get() = position > 1

    /** The document to seed its viewmodel with, or null for an id the mode no longer knows. */
    val current: DocListItem? get() = known[currentId]
}

/** The Inbox triage mode's movement and exit, as its screen and keys call them. */
interface TriageActions {
    fun exitTriage()

    /** Move on without marking anything: a skip. At the last document this stays put. */
    fun triageNext()

    /** Move back without marking anything: a skip backwards. At the first document this stays put. */
    fun triagePrevious()

    /** The document was marked reviewed and its draft saved: move on, or finish at the last one. */
    fun triageReviewedAndAdvance()
}
