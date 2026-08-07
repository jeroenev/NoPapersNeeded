package dev.npn.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_documents
import no_papers_needed.composeapp.generated.resources.library_settings
import no_papers_needed.composeapp.generated.resources.library_tags
import no_papers_needed.composeapp.generated.resources.library_trash
import org.jetbrains.compose.resources.StringResource

/** Top-level destinations reachable from the navigation shell (rail on wide, bar on compact). */
enum class TopLevelDestination(
    val label: StringResource,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    LIBRARY(Res.string.library_documents, Icons.Outlined.Home, Icons.Filled.Home),
    TAGS(Res.string.library_tags, Icons.AutoMirrored.Outlined.Label, Icons.AutoMirrored.Filled.Label),
    TRASH(Res.string.library_trash, Icons.Outlined.Delete, Icons.Filled.Delete),
    SETTINGS(Res.string.library_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/**
 * AI destinations that live in the navigation rail on wide layouts. They are not part of
 * [TopLevelDestination] because the compact bottom bar does not carry them, but the rail keeps
 * itself visible (with the matching item selected) while one of them is open.
 */
enum class AiDestination { CHAT, EDIT }
