package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

actual object LocalAppLocale {
    // The system locale, read when the object initializes: before the first provides replaces it.
    private val default: Locale = Locale.getDefault()

    // The provided configuration's locale, which a language change recomposes readers of.
    actual val current: String
        @Composable get() = LocalConfiguration.current.locales[0].toString()

    @Suppress("DEPRECATION")
    @Composable
    @ReadOnlyComposable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val configuration = LocalConfiguration.current
        val new =
            when (value) {
                null -> default
                else -> Locale(value)
            }
        Locale.setDefault(new)
        configuration.setLocale(new)
        val resources = LocalContext.current.resources
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return LocalConfiguration.provides(configuration)
    }
}
