package dev.npn.app.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/**
 * The page transition each platform's navigation host uses by default, so reduced motion can replace
 * it with no transition without changing what every other user sees. Android cross-fades for 700ms;
 * desktop changes screens at once.
 */
internal expect fun defaultPageEnter(): EnterTransition

internal expect fun defaultPageExit(): ExitTransition
