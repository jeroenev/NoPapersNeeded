package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Whether the app should reduce motion right now; see [LocalReducedMotion]. */
val reducedMotion: Boolean
    @Composable get() = LocalReducedMotion.current

/**
 * Provide [LocalReducedMotion] for everything below: the user asked for it ([preference]), or this
 * platform reports its animations off. Read per composition, so a platform change (Android's
 * "Remove animations") is picked up when the app next composes rather than only at start.
 */
@Composable
fun ProvideReducedMotion(
    preference: Boolean,
    content: @Composable () -> Unit,
) {
    // The platform query is not observable. Asking it once per preference value is enough: changing
    // the system setting sends the activity through a configuration change, which recomposes this.
    val animationsEnabled = remember(preference) { systemAnimationsEnabled() }
    CompositionLocalProvider(
        LocalReducedMotion provides reducesMotion(preference, animationsEnabled),
        content = content,
    )
}
