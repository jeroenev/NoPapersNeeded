package dev.npn.app.ui.ai

/** How an Edit Run is sized and where it runs: the AI edit screen's own defaults and bounds. */
object AiEditLimits {
    /**
     * Bounds for how many documents one bulk-tagging run processes. This cap is per run and
     * adjustable on the AI edit page (Idle phase); the remainder carries over to the next batch.
     */
    const val MIN_BATCH_DOCS = 1
    const val MAX_BATCH_DOCS = 100
    const val DEFAULT_BATCH_DOCS = 25
    val BATCH_RANGE = MIN_BATCH_DOCS..MAX_BATCH_DOCS

    /**
     * Above how many documents an Edit Run is queued to the background by default. Only a default
     * — the user can flip it either way per run.
     *
     * The number is a guess at where waiting stops being reasonable: a handful of documents comes
     * back while the user is still looking at the page, and watching that is the better experience
     * than being sent to a queue. Past that the run is minutes of one model call per document,
     * which is not something to hold a screen open for.
     */
    const val BACKGROUND_THRESHOLD_DOCS = 10
}
