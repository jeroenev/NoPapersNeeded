package dev.npn.app.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/** Desktop's navigation-compose default: no page transition at all. */
internal actual fun defaultPageEnter(): EnterTransition = EnterTransition.None

internal actual fun defaultPageExit(): ExitTransition = ExitTransition.None
