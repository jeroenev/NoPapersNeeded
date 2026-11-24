package dev.npn.app.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut

/** Android's navigation-compose default: a 700ms cross-fade. */
internal actual fun defaultPageEnter(): EnterTransition = fadeIn(tween(700))

internal actual fun defaultPageExit(): ExitTransition = fadeOut(tween(700))

/** Android's navigation-compose predictive-back default: the page below fades in... */
internal actual fun defaultPredictivePopEnter(): EnterTransition = fadeIn(spring(dampingRatio = 1f, stiffness = 1600f))

/** ...while the leaving page shrinks to 70% under the finger. */
internal actual fun defaultPredictivePopExit(): ExitTransition = scaleOut(targetScale = 0.7f)
