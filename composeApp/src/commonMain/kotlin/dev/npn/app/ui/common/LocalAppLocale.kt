package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.key

/**
 * CompositionLocal used to override the locale that Compose resource lookups
 * ([stringResource] and friends) resolve against. Providing null falls back to the platform's
 * default locale. This is a temporary workaround for the missing common resource-locale API
 * (see https://kotlinlang.org/docs/multiplatform/compose-resource-environment.html).
 */
expect object LocalAppLocale {
    val current: String
        @Composable get

    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

/**
 * Applies the app-language override [language] (null follows the platform's) to the whole subtree.
 * Changing the language rebuilds the subtree via [key], so resource lookups re-resolve against the
 * new locale. The caller passes the settings' current value from the first frame on, so a recreated
 * window composes once, in its language, rather than once in the platform's and again in its own.
 */
@Composable
fun LocaleProvider(
    language: String?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppLocale provides language) {
        key(language) { content() }
    }
}
