package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

// Typed detekt reports both private properties unused although the members below read them: a false
// positive of its plugin-less analysis next to an `actual` declaration.
@Suppress("UnusedPrivateProperty")
actual object LocalAppLocale {
    // The system locale, read when the object initializes: before the first provides replaces it.
    private val default: Locale = Locale.getDefault()
    private val currentLocale = staticCompositionLocalOf { Locale.getDefault().toString() }

    actual val current: String
        @Composable get() = currentLocale.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val new =
            when (value) {
                null -> default
                else -> Locale(value)
            }
        Locale.setDefault(new)
        return currentLocale.provides(new.toString())
    }
}
