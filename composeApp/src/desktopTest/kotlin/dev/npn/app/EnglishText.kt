package dev.npn.app

import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.resolve
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import java.util.Locale

/**
 * The resource environment of [locale], for desktop tests that check the wording a user reads.
 * Taken from the system environment while the JVM's default locale is briefly [locale], so it holds
 * whatever locale the machine, the IDE or the Gradle test JVM is in. Desktop only: on the Android
 * host JVM the system environment asks `Resources.getSystem()`, which `android.jar` stubs out.
 */
fun resourcesIn(locale: Locale): ResourceEnvironment {
    val previous = Locale.getDefault()
    Locale.setDefault(locale)
    try {
        return getSystemResourceEnvironment()
    } finally {
        Locale.setDefault(previous)
    }
}

/** The default `values/` resources. */
val English: ResourceEnvironment by lazy { resourcesIn(Locale.US) }

suspend fun UiText.english(): String = resolve(English)

suspend fun UiMessage.english(): String = text.english()
