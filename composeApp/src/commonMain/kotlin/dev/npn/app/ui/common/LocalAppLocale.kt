package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.settings.SettingsSnapshot

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
 * Applies the [SettingsRepository]'s app-language override to the whole subtree. Changing the
 * language rebuilds the subtree via [key], so resource lookups re-resolve against the new locale.
 */
@Composable
fun LocaleProvider(
    settings: SettingsRepository,
    content: @Composable () -> Unit,
) {
    val snapshot: SettingsSnapshot? by settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val language = snapshot?.appLanguage
    CompositionLocalProvider(LocalAppLocale provides language) {
        key(language) { content() }
    }
}
