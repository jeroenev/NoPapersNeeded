package dev.npn.core.ocr

/**
 * Where a document stands in the OCR pipeline, as a closed vocabulary and the one source of its
 * names. [column] is the spelling stored in `documents.ocrState` (a plain TEXT column); the enum is
 * what crosses the `dev.npn.core.app` read seam, so adding a state is a compile error at every UI
 * site that labels one rather than a raw token shipped to users.
 *
 * It mirrors this device's OCR queue row (`ocr_queue.state`, [dev.npn.core.db.QueueState], in
 * `device.db`) for the index, which a rebuild loses while the queue outlives it. The combinations
 * that stand once a pass is over, and who makes each:
 *
 * - [NONE]: no queue row, or a `done` one. A rebuilt index starts every row here, whatever the
 *   queue holds, until `OcrFacadeImpl.reconcileAfterRescan` after the next rescan queues the
 *   document (no row, or `done`) or copies the row's `pending` or `failed` over. [OcrPipeline]
 *   puts a [PENDING] document back here, with its row deleted, once it is trashed or deleted.
 * - [PENDING]: row `pending`, a run owed now or after its backoff. Set when a run is queued: an
 *   import (`ImportService`), a re-run or AI request or a rescan's queueing (`OcrFacadeImpl`),
 *   and by [OcrPipeline]'s failed pass below the attempt cap that indexed no text.
 * - [FAILED]: row `failed`, given up on at the attempt cap with no text indexed; [OcrPipeline]'s
 *   last failed pass sets both. A re-run queues it again ([PENDING]).
 * - [DONE] / [ADOPTED]: text is indexed, from this device's sidecar or another's
 *   (`OcrPipeline.indexAndMarkSelected`, from a drained run or a rescan's adoption). The row is
 *   gone (adoption drops one nothing is owed on), `done` (a drained run), or still `pending` or
 *   `failed` for a run owed beside the indexed text: a background upgrade (`maybeQueueUpgrade`),
 *   or this device's run (a re-run, an AI request) whose failed pass indexed the best stored
 *   version all the same. The row's state then never reaches the document's, which describes the
 *   searchable text. Queueing a run on purpose (above) does set [PENDING] until its pass is over.
 */
enum class OcrState(
    val column: String,
) {
    /** Never processed: no sidecar on any device and nothing queued here. */
    NONE("none"),

    /** Queued for this device's engine. */
    PENDING("pending"),

    /** This device produced the indexed text. */
    DONE("done"),

    /** This device tried and gave up after [OcrPipeline.MAX_ATTEMPTS]. */
    FAILED("failed"),

    /** Indexed text came from another device's sidecar, not from a run here. */
    ADOPTED("adopted"),
    ;

    companion object {
        private val byColumn = entries.associateBy { it.column }

        /**
         * Decode an `ocrState` column value. Unknown values — a row written by a newer build —
         * decode to [NONE] rather than throwing: the index is rebuildable, but a stale row must
         * never crash a read.
         */
        fun fromColumn(value: String?): OcrState = byColumn[value] ?: NONE
    }
}
