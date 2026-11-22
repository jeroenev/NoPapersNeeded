package dev.npn.app.platform

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A system unlock prompt, where the platform has one: Android offers the device's biometrics or
 * its own screen lock, which is always a fallback when the app's PIN is forgotten. Desktop has
 * none, so the app's PIN is the only way in there.
 *
 * Provided by the platform entry point through [LocalAppLockAuthenticator]; the shared lock screen
 * shows its button only when [isAvailable] is true.
 */
interface AppLockAuthenticator {
    /** Whether the device currently offers a biometric or device-credential unlock. */
    fun isAvailable(): Boolean

    /**
     * Show the system prompt. [onResult] is called exactly once with true when the user proved
     * their identity, and false when they cancelled or the prompt failed.
     */
    fun authenticate(
        title: String,
        subtitle: String,
        onResult: (Boolean) -> Unit,
    )
}

/** The platform's system unlock, or null where the app's own PIN is the only unlock. */
val LocalAppLockAuthenticator = staticCompositionLocalOf<AppLockAuthenticator?> { null }
