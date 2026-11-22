package dev.npn

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.npn.app.platform.AppLockAuthenticator

/**
 * Android's system unlock for the app lock: biometrics with the device's own screen lock as the
 * fallback, through [BiometricPrompt]. The device credential is always among the allowed
 * authenticators, so a user who forgets the app PIN can still get in with the phone's PIN, pattern
 * or password — the app lock never becomes a door nobody can open.
 */
class AndroidAppLockAuthenticator(
    private val activity: FragmentActivity,
) : AppLockAuthenticator {
    override fun isAvailable(): Boolean =
        BiometricManager
            .from(activity)
            .canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    override fun authenticate(
        title: String,
        subtitle: String,
        onResult: (Boolean) -> Unit,
    ) {
        if (!isAvailable()) {
            onResult(false)
            return
        }
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    onResult(false)
                }

                // A finger that did not match: the prompt stays open for another try.
                override fun onAuthenticationFailed() = Unit
            }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                // No negative button: DEVICE_CREDENTIAL supplies the system's own "use PIN" action.
                .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
                .build()
        prompt.authenticate(info)
    }

    private companion object {
        val ALLOWED_AUTHENTICATORS: Int =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
