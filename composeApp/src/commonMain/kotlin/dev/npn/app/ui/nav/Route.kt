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

    /** The read-only OCR / AI-enrichment backlog. Wide layouts show it in the library side panel. */
    @Serializable
    data object Queue : Route

    @Serializable
    data object AiChat : Route

    @Serializable
    data object AiEdit : Route
}
