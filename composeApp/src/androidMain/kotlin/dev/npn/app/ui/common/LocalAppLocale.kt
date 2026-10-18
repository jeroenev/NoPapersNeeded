package dev.npn.app.ui.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

actual object LocalAppLocale {
    // The provided configuration's locale, which a language change recomposes readers of.
    actual val current: String
        @Composable get() = LocalConfiguration.current.locales[0].toString()

    @Suppress("DEPRECATION")
    @Composable
    @ReadOnlyComposable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val configuration = LocalConfiguration.current
        // Without an override, the system's own locale, which neither Locale.setDefault nor the
        // app's configuration below changes.
        val new =
            when (value) {
                null -> Resources.getSystem().configuration.locales[0]
                else -> Locale(value)
            }
        Locale.setDefault(new)
        configuration.setLocale(new)
        val resources = LocalResources.current
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return LocalConfiguration.provides(configuration)
    }
}
