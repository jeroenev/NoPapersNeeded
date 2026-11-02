package dev.npn.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_documents
import no_papers_needed.composeapp.generated.resources.library_settings
import no_papers_needed.composeapp.generated.resources.library_tags
import no_papers_needed.composeapp.generated.resources.library_trash
import no_papers_needed.composeapp.generated.resources.nav_assistant
import no_papers_needed.composeapp.generated.resources.queue_nav_label
import org.jetbrains.compose.resources.StringResource

/**
 * Top-level destinations reachable from the navigation shell (rail on wide, bar on compact).
 *
 * A bottom bar holds three to five items of comparable weight, and the app has more top-level
 * screens than that. Rather than shrink labels or evict a screen into the settings page, the
 * compact shell splits them: the screens you move between while working ([compactBar], then the
 * assistant and [ACTIVITY]) keep their own bar item, and the ones you visit deliberately and
 * occasionally ([compactMore]) live one tap deeper, in the bar's "More" sheet. The rail has room
 * for all of them. [ACTIVITY] carries the app's one "working in the background" indicator, which
 * is why it keeps a bar item of its own; [SETTINGS] closes the rail and the sheet alike.
 */
enum class TopLevelDestination(
    val label: StringResource,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    LIBRARY(Res.string.library_documents, Icons.Outlined.Home, Icons.Filled.Home),
    TAGS(Res.string.library_tags, Icons.AutoMirrored.Outlined.Label, Icons.AutoMirrored.Filled.Label),
    TRASH(Res.string.library_trash, Icons.Outlined.Delete, Icons.Filled.Delete),

    /** What the app is doing in the background, problems first; the processing queue's page. */
    ACTIVITY(Res.string.queue_nav_label, Icons.Outlined.Schedule, Icons.Filled.Schedule),
    SETTINGS(Res.string.library_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
    ;

    companion object {
        /** The rail's working screens, at its top in this order; the assistant follows them. */
        val rail: List<TopLevelDestination> = listOf(LIBRARY, TAGS, TRASH)

        /** The destinations with their own item on the compact bottom bar ahead of the assistant. */
        val compactBar: List<TopLevelDestination> = listOf(LIBRARY, TAGS)

        /** The destinations behind the compact bar's "More" item, in sheet order (settings last). */
        val compactMore: List<TopLevelDestination> = listOf(TRASH)
    }
}

/**
 * The assistant: one shell item for its two modes, the chat and bulk edit, which stay two routes
 * underneath ([Route.AiChat], [Route.AiEdit]) with a segmented control between them. It is not a
 * [TopLevelDestination] because it shows only while AI is configured (or one of its screens is
 * open) and its screens are pushed above Library rather than restored like the others.
 */
object AssistantDestination {
    val label: StringResource = Res.string.nav_assistant
    val icon: ImageVector = Icons.Outlined.AutoAwesome
    val selectedIcon: ImageVector = Icons.Filled.AutoAwesome
}
