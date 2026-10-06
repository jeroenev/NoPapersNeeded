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

    /**
     * Settings, optionally with one category pane open. Carrying the category in the route (rather
     * than in screen state) is what makes drill-down on compact layouts answer to system back and
     * survive process death, and lets other screens deep-link straight to the pane they mean.
     * The value is a [dev.npn.app.ui.settings.SettingsCategoryId] route key; unknown keys from an
     * older back stack fall back to the table of contents.
     */
    @Serializable
    data class Settings(
        val category: String? = null,
    ) : Route

    @Serializable
    data object AutoMatchRules : Route

    /**
     * The processing queue: the OCR / AI-enrichment backlog plus the AI edit runs handed to the
     * background. A top-level destination ([TopLevelDestination.QUEUE]).
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
