package dev.npn.app.ui.nav

import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController

/**
 * Show [dest], from the shell or from anywhere that opens a top-level screen (the library's banners,
 * the assistant's "open all documents"). One branch at a time: whatever is above Library is put
 * away, its state saved, so a destination comes back as it was left. Library itself is never
 * restored: its own stack holds nothing worth bringing back, and the saved stack a pop maps to it
 * is whatever was on top, which would cover the library again.
 */
internal fun NavHostController.navigateTopLevel(dest: TopLevelDestination) {
    dropAiEdit()
    val route: Route =
        when (dest) {
            TopLevelDestination.LIBRARY -> Route.Library
            TopLevelDestination.TAGS -> Route.TagManagement
            TopLevelDestination.TRASH -> Route.Trash
            TopLevelDestination.ACTIVITY -> Route.Queue
            TopLevelDestination.SETTINGS -> Route.Settings()
        }
    navigate(route) {
        popUpTo(Route.Library) { saveState = true }
        launchSingleTop = true
        restoreState = dest != TopLevelDestination.LIBRARY
    }
}

/**
 * The assistant's screens (the chat, bulk edit) sit one level above Library, never stacked on other
 * pushed screens or each other, so switching between its two modes replaces one with the other.
 */
internal fun NavHostController.navigateAi(
    route: Route,
    restoreState: Boolean = false,
) {
    dropAiEdit()
    navigate(route) {
        popUpTo(Route.Library) { saveState = true }
        launchSingleTop = true
        this.restoreState = restoreState
    }
}

/**
 * Pop AI edit, when it is the screen shown, without saving it. It always opens afresh, so a saved
 * copy would never come back, and would keep its viewmodel, and a batch that viewmodel was running,
 * alive until the library closes.
 */
private fun NavHostController.dropAiEdit() {
    if (currentBackStackEntry?.destination?.hasRoute<Route.AiEdit>() == true) {
        popBackStack<Route.AiEdit>(inclusive = true, saveState = false)
    }
}
