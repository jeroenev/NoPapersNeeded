package dev.npn.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
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
import no_papers_needed.composeapp.generated.resources.queue_nav_label
import org.jetbrains.compose.resources.StringResource

/**
 * Top-level destinations reachable from the navigation shell (rail on wide, bar on compact).
 *
 * The rail renders every entry; the compact bottom bar deliberately renders a hand-picked subset
 * (see [dev.npn.app.ui.nav.NavigationShell]) because Material caps it at five items. [QUEUE] is
 * one of the entries that stays off the bar — on compact layouts the processing queue is reached
 * from settings instead.
 */
enum class TopLevelDestination(
    val label: StringResource,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    LIBRARY(Res.string.library_documents, Icons.Outlined.Home, Icons.Filled.Home),
    TAGS(Res.string.library_tags, Icons.AutoMirrored.Outlined.Label, Icons.AutoMirrored.Filled.Label),
    TRASH(Res.string.library_trash, Icons.Outlined.Delete, Icons.Filled.Delete),
    QUEUE(Res.string.queue_nav_label, Icons.Outlined.Schedule, Icons.Filled.Schedule),
    SETTINGS(Res.string.library_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/**
 * AI destinations that live alongside the top-level ones in the navigation shell (rail on wide,
 * bottom bar on compact). They are not part of [TopLevelDestination] because they are screens
 * pushed above Library, but the shell keeps itself visible (with the matching item selected) while
 * one of them is open, mirroring the rail on compact layouts.
 */
enum class AiDestination { CHAT, EDIT }
