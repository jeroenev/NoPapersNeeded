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
     * older back stack fall back to the table of contents. [revealAdvanced] opens the pane with its
     * Advanced disclosure expanded, for a settings search that found something behind it.
     */
    @Serializable
    data class Settings(
        val category: String? = null,
        val revealAdvanced: Boolean = false,
    ) : Route

    @Serializable
    data object AutoMatchRules : Route

    /**
     * The AI spend of one kind of work, opened from the AI settings pane. [kind] is an
     * [dev.npn.app.ui.settings.AiSpendKinds] key; an unknown one shows the "Other" kind.
     */
    @Serializable
    data class AiSpendKind(
        val kind: String,
    ) : Route

    /** The devices that share the library, and their retirement, opened from the Library settings pane. */
    @Serializable
    data object Devices : Route

    /** The reminder window over the library's watched dates, opened from the Inbox area. */
    @Serializable
    data object Upcoming : Route

    /**
     * Activity, the processing queue's page: problems first, then the text recognition and
     * Auto-organize backlog, the bulk edits handed to the background and the cloud sync. A
     * top-level destination ([TopLevelDestination.ACTIVITY]).
     */
    @Serializable
    data object Queue : Route

    /** The assistant's chat mode ([AssistantDestination]). */
    @Serializable
    data object AiChat : Route

    /** The assistant's bulk edit mode ([AssistantDestination]). */
    @Serializable
    data object AiEdit : Route

    /** Review of one finished background bulk edit, opened from Activity. */
    @Serializable
    data class AiEditReview(
        val jobId: String,
    ) : Route
}
