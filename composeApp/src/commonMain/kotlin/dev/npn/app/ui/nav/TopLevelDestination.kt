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
 * Where a destination sits on a compact layout, where the shell cannot afford one slot per
 * destination.
 *
 * A bottom bar holds three to five items of comparable weight, and the app has more top-level
 * screens than that. Rather than shrink labels or evict a screen into the settings page, the
 * compact shell splits them: the screens you move between while working stay on the [BAR], and
 * the ones you visit deliberately and occasionally live one tap deeper, in the [MORE] sheet.
 */
enum class CompactPlacement {
    /** Rendered as a permanent bottom-bar item. */
    BAR,

    /** Rendered as a row in the bottom bar's "More" sheet. */
    MORE,
}

/**
 * Top-level destinations reachable from the navigation shell (rail on wide, bar on compact).
 *
 * The rail renders every entry — it has the vertical room. The compact bottom bar renders the
 * [CompactPlacement.BAR] entries and reaches the rest through its "More" sheet, so every
 * destination stays one or two taps away on every layout and no screen has to hide inside
 * settings to be reachable.
 */
enum class TopLevelDestination(
    val label: StringResource,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val compactPlacement: CompactPlacement,
) {
    LIBRARY(
        Res.string.library_documents,
        Icons.Outlined.Home,
        Icons.Filled.Home,
        CompactPlacement.BAR,
    ),
    TAGS(
        Res.string.library_tags,
        Icons.AutoMirrored.Outlined.Label,
        Icons.AutoMirrored.Filled.Label,
        CompactPlacement.BAR,
    ),
    TRASH(
        Res.string.library_trash,
        Icons.Outlined.Delete,
        Icons.Filled.Delete,
        CompactPlacement.MORE,
    ),
    QUEUE(
        Res.string.queue_nav_label,
        Icons.Outlined.Schedule,
        Icons.Filled.Schedule,
        CompactPlacement.MORE,
    ),
    SETTINGS(
        Res.string.library_settings,
        Icons.Outlined.Settings,
        Icons.Filled.Settings,
        CompactPlacement.MORE,
    ),
    ;

    companion object {
        /** Destinations with their own item on the compact bottom bar, in bar order. */
        val compactBar: List<TopLevelDestination> =
            entries.filter { it.compactPlacement == CompactPlacement.BAR }

        /** Destinations reached through the compact bottom bar's "More" sheet, in sheet order. */
        val compactMore: List<TopLevelDestination> =
            entries.filter { it.compactPlacement == CompactPlacement.MORE }
    }
}

/**
 * AI destinations that live alongside the top-level ones in the navigation shell (rail on wide,
 * bottom bar on compact). They are not part of [TopLevelDestination] because they are screens
 * pushed above Library, but the shell keeps itself visible (with the matching item selected) while
 * one of them is open, mirroring the rail on compact layouts.
 *
 * The rail gives both their own item. The compact bar has one slot to spare and gives it to
 * [CHAT], the AI screen you open cold; [EDIT] is usually entered from the library's selection mode
 * with documents already in hand, so it rides in the bar's "More" sheet instead.
 */
enum class AiDestination { CHAT, EDIT }
