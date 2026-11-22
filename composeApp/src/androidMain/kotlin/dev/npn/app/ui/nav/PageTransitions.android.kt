package dev.npn.app.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/** Android's navigation-compose default: a 700ms cross-fade. */
internal actual fun defaultPageEnter(): EnterTransition = fadeIn(tween(700))

internal actual fun defaultPageExit(): ExitTransition = fadeOut(tween(700))
