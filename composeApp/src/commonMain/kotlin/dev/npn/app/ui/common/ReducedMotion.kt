package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.npn.app.platform.systemAnimationsEnabled
import dev.npn.core.settings.reducesMotion

/**
 * Whether this composition should skip its non-essential transitions: the user's Appearance
 * setting, or the platform's own animation setting ([systemAnimationsEnabled]). Provided once above
 * the app ([ProvideReducedMotion]); every screen reads it through [reducedMotion].
 *
 * A screen that animates a transition nobody needs to follow (a page slide, an expanding row, a
 * list scrolled into view) asks this first and moves to its destination at once when it is true.
 */
val LocalReducedMotion = compositionLocalOf { false }

/** Whether the app should reduce motion right now; see [LocalReducedMotion]. */
val reducedMotion: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalReducedMotion.current

/**
 * Provide [LocalReducedMotion] for everything below: the user asked for it ([preference]), or this
 * platform reports its animations off. [readSystemAnimations] is a seam for tests; production leaves
 * it as the platform query.
 *
 * The platform answer is asked again whenever the app resumes, so turning "Remove animations" on in
 * Android settings takes effect when the user comes back instead of at the next start: Android
 * updates the animator scale without recreating the activity.
 */
@Composable
fun ProvideReducedMotion(
    preference: Boolean,
    readSystemAnimations: () -> Boolean = ::systemAnimationsEnabled,
    content: @Composable () -> Unit,
) {
    var animationsEnabled by remember { mutableStateOf(readSystemAnimations()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { animationsEnabled = readSystemAnimations() }
    CompositionLocalProvider(
        LocalReducedMotion provides reducesMotion(preference, animationsEnabled),
        content = content,
    )
}
