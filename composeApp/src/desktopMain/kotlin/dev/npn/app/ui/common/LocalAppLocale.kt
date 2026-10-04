package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

// Typed detekt reports both private properties unused although the members below read them: a false
// positive of its plugin-less analysis next to an `actual` declaration.
@Suppress("UnusedPrivateProperty")
actual object LocalAppLocale {
    private var default: Locale? = null
    private val currentLocale = staticCompositionLocalOf { Locale.getDefault().toString() }

    actual val current: String
        @Composable get() = currentLocale.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        if (default == null) default = Locale.getDefault()
        val new =
            when (value) {
                null -> default!!
                else -> Locale(value)
            }
        Locale.setDefault(new)
        return currentLocale.provides(new.toString())
    }
}
