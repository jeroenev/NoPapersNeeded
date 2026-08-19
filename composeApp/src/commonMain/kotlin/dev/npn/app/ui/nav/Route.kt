package dev.npn.app.ui.nav

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations for navigation-compose. */
sealed interface Route {
    @Serializable
    data object Library : Route

    @Serializable
    data class DocDetail(
        val docId: String,
    ) : Route

    @Serializable
    data object TagManagement : Route

    @Serializable
    data object Trash : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object AutoMatchRules : Route

    /**
     * The processing queue: the OCR / AI-enrichment backlog plus the AI edit runs handed to the
     * background. Wide layouts show it in the library side panel.
     */
    @Serializable
    data object Queue : Route

    @Serializable
    data object AiChat : Route

    @Serializable
    data object AiEdit : Route

    /** Review of one finished background AI edit run, opened from the processing queue. */
    @Serializable
    data class AiEditReview(
        val jobId: String,
    ) : Route
}
