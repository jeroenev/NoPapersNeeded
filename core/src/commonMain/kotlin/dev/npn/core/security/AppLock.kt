package dev.npn.core.security

import dev.npn.core.util.EpochClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How long the app may stay in the background before it asks for the unlock secret again.
 *
 * The intervals are the choices Settings offers. [IMMEDIATELY] locks as soon as the app returns
 * from the background; the others allow that much time away. On desktop, where there is no
 * background, the app treats a minimized window as the background and also locks after the
 * chosen interval without input (except for [IMMEDIATELY], which only acts on minimizing).
 */
enum class LockTimeout(
    val millis: Long,
) {
    IMMEDIATELY(0L),
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(5L * 60_000L),
    FIFTEEN_MINUTES(15L * 60_000L),
    ;

    companion object {
        /** A stored name, read back as [IMMEDIATELY] when it is absent or unknown. */
        fun fromWire(value: String?): LockTimeout = entries.firstOrNull { it.name == value } ?: IMMEDIATELY
    }
}

/**
 * A salted, slow hash of the app's unlock secret. The secret itself is never stored anywhere: this
 * is what a settings file holds, and [LockCrypto.verify] is the only way back from it.
 *
 * [iterations] travels with the hash so a later build can raise the production cost without
 * invalidating credentials already saved.
 */
data class LockCredential(
    val scheme: String,
    val iterations: Int,
    val saltHex: String,
    val hashHex: String,
) {
    /** Whether this is a credential this build can verify, and a plausible one at that. */
    fun isValid(): Boolean =
        scheme == LockCrypto.SCHEME &&
            iterations in LockCrypto.MIN_ITERATIONS..LockCrypto.MAX_ITERATIONS &&
            saltHex.hexToBytesOrNull()?.size == LockCrypto.SALT_BYTES &&
            hashHex.hexToBytesOrNull()?.size == LockCrypto.HASH_BYTES

    /** A single settings value; the fields are all fixed-shape hex and a known scheme. */
    fun encode(): String = "$scheme:$iterations:$saltHex:$hashHex"

    companion object {
        /** Read [LockCredential.encode]'s value, or null when it is absent or malformed. */
        fun decode(value: String?): LockCredential? {
            val parts = value?.split(':', limit = 4) ?: return null
            if (parts.size != 4) return null
            val credential =
                LockCredential(
                    scheme = parts[0],
                    iterations = parts[1].toIntOrNull() ?: return null,
                    saltHex = parts[2],
                    hashHex = parts[3],
                )
            return credential.takeIf { it.isValid() }
        }
    }
}

/** This device's app-lock setting. The credential is a hash ([LockCredential]); never the secret. */
data class AppLockSettings(
    val enabled: Boolean = false,
    val timeout: LockTimeout = LockTimeout.IMMEDIATELY,
    val credential: LockCredential? = null,
) {
    /**
     * The setting as it may be stored: an enabled lock needs a credential this build can verify.
     * A hash that went missing or does not read disables the lock rather than leaving a door no
     * secret opens, and the credential is dropped while the lock is off.
     */
    fun normalised(): AppLockSettings {
        val usable = credential?.takeIf { it.isValid() }
        return if (enabled && usable != null) copy(credential = usable) else copy(enabled = false, credential = null)
    }
}

/** What the lock screen shows and what the shell acts on. */
data class AppLockState(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val timeout: LockTimeout = LockTimeout.IMMEDIATELY,
    val failedAttempts: Int = 0,
    /** When a retry is allowed again, or null while attempts are accepted. */
    val retryAtMillis: Long? = null,
    /** The hash an attempt is checked against; never the secret. */
    internal val credential: LockCredential? = null,
    internal val backgroundAtMillis: Long? = null,
    internal val lastInteractionMillis: Long = 0L,
)

/** How an unlock attempt ended. */
sealed interface UnlockResult {
    /** The secret matched; the app is unlocked. */
    data object Success : UnlockResult

    /** The secret did not match. [UnlockResult.TooManyAttempts] carries a wait, if one applies. */
    data object WrongSecret : UnlockResult

    /** Attempts are held back for [remainingMillis] after repeated failures. */
    data class TooManyAttempts(
        val remainingMillis: Long,
    ) : UnlockResult

    /** No lock is configured, so nothing was checked. */
    data object Disabled : UnlockResult

    /** The lock was enabled without a credential this build can verify; it was cleared. */
    data object Misconfigured : UnlockResult
}

/**
 * The app-lock decision state: one machine for the shell, the lock screen and tests, with the wall
 * clock injected so "one minute in the background" is a fact about the clock rather than the test
 * runner.
 *
 * The lock engages when the app starts (a cold start always shows it) and when the app returns
 * from the background after [AppLockState.timeout]. [onInteraction] and [checkIdle] add the
 * desktop idle rule. Disabling the lock, or setting a new secret, unlocks and resets attempts:
 * those happen while the user is in the app, which is proof enough that they are the owner.
 *
 * Transitions go through one [MutableStateFlow], so the lock is safe to read from the shell on
 * another thread while the secret is verified off the main thread.
 */
class AppLock(
    initial: AppLockSettings,
    private val clock: EpochClock,
    private val verify: (String, LockCredential) -> Boolean = LockCrypto::verify,
) {
    private val normalised = initial.normalised()
    private val mutable =
        MutableStateFlow(
            AppLockState(
                enabled = normalised.enabled,
                locked = normalised.enabled,
                timeout = normalised.timeout,
                credential = normalised.credential,
                lastInteractionMillis = clock.nowMillis(),
            ),
        )

    val state: StateFlow<AppLockState> = mutable.asStateFlow()

    /**
     * Follow the saved setting. Turning the lock on, changing its secret or choosing another
     * timeout keeps the app open: the change was made from inside the unlocked app. Turning it off
     * clears the hash and every failed attempt.
     */
    fun updateSettings(settings: AppLockSettings) {
        val next = settings.normalised()
        mutable.update { current ->
            when {
                !next.enabled -> {
                    current.copy(
                        enabled = false,
                        locked = false,
                        timeout = next.timeout,
                        credential = null,
                        failedAttempts = 0,
                        retryAtMillis = null,
                        backgroundAtMillis = null,
                    )
                }

                current.credential != next.credential -> {
                    current.copy(
                        enabled = true,
                        locked = false,
                        timeout = next.timeout,
                        credential = next.credential,
                        failedAttempts = 0,
                        retryAtMillis = null,
                        backgroundAtMillis = null,
                        lastInteractionMillis = clock.nowMillis(),
                    )
                }

                else -> {
                    current.copy(enabled = true, timeout = next.timeout, credential = next.credential)
                }
            }
        }
    }

    /** The app left the foreground (Android) or was minimized (desktop). */
    fun onBackground() {
        mutable.update { it.copy(backgroundAtMillis = clock.nowMillis()) }
    }

    /** The app returned; locks once it was away for at least the chosen interval. */
    fun onForeground() {
        val now = clock.nowMillis()
        mutable.update { current ->
            val awaySince = current.backgroundAtMillis
            val lock =
                current.enabled &&
                    !current.locked &&
                    awaySince != null &&
                    now - awaySince >= current.timeout.millis
            current.copy(
                locked = current.locked || lock,
                backgroundAtMillis = null,
                lastInteractionMillis = if (lock) now else current.lastInteractionMillis,
            )
        }
    }

    /** The user did something (desktop input); the idle timer starts over. */
    fun onInteraction() {
        mutable.update { it.copy(lastInteractionMillis = clock.nowMillis()) }
    }

    /**
     * The desktop idle check: locks after [AppLockState.timeout] without input. [LockTimeout.IMMEDIATELY]
     * adds nothing here, since locking the instant the user touches nothing would make the app
     * unusable; minimizing is its trigger instead ([onBackground]).
     */
    fun checkIdle() {
        val now = clock.nowMillis()
        mutable.update { current ->
            val idle =
                current.enabled &&
                    !current.locked &&
                    current.timeout.millis > 0 &&
                    now - current.lastInteractionMillis >= current.timeout.millis
            if (idle) current.copy(locked = true) else current
        }
    }

    /** Lock now, for an explicit lock action; a disabled lock stays open. */
    fun lockNow() {
        mutable.update { if (it.enabled) it.copy(locked = true) else it }
    }

    /**
     * Check [secret] against the saved hash. Repeated wrong secrets hold the next attempt back
     * ([LockBackoff]), so a four-digit PIN cannot be walked through at typing speed.
     */
    fun unlockWithSecret(secret: String): UnlockResult {
        val now = clock.nowMillis()
        val before = mutable.value
        if (!before.enabled) return UnlockResult.Disabled
        before.retryAtMillis?.let { retryAt -> if (now < retryAt) return UnlockResult.TooManyAttempts(retryAt - now) }
        val credential = before.credential ?: return clearMisconfigured()
        val correct = verify(secret, credential)

        var outcome: UnlockResult = UnlockResult.WrongSecret
        mutable.update { current ->
            when {
                !current.enabled -> {
                    outcome = UnlockResult.Disabled
                    current.copy(locked = false)
                }

                // The setting changed while the slow hash ran: do not judge this attempt against it.
                current.credential != credential -> {
                    outcome = UnlockResult.Misconfigured
                    current.copy(
                        locked = false,
                        failedAttempts = 0,
                        retryAtMillis = null,
                        credential = null,
                        enabled = false,
                    )
                }

                current.retryAtMillis?.let { it > now } == true -> {
                    outcome = UnlockResult.TooManyAttempts(current.retryAtMillis - now)
                    current
                }

                correct -> {
                    outcome = UnlockResult.Success
                    current.copy(
                        locked = false,
                        failedAttempts = 0,
                        retryAtMillis = null,
                        backgroundAtMillis = null,
                        lastInteractionMillis = now,
                    )
                }

                else -> {
                    val failures = current.failedAttempts + 1
                    val wait = LockBackoff.retryDelayMillis(failures)
                    outcome = UnlockResult.WrongSecret
                    current.copy(
                        failedAttempts = failures,
                        retryAtMillis = if (wait > 0) now + wait else null,
                    )
                }
            }
        }
        return outcome
    }

    /** The platform proved the user's identity (Android biometrics or the device's screen lock). */
    fun unlockWithSystem() {
        mutable.update {
            if (it.enabled) {
                it.copy(
                    locked = false,
                    failedAttempts = 0,
                    retryAtMillis = null,
                    backgroundAtMillis = null,
                    lastInteractionMillis = clock.nowMillis(),
                )
            } else {
                it
            }
        }
    }

    /** How long until another attempt is accepted; 0 when one is accepted now. */
    fun retryRemainingMillis(): Long {
        val retryAt = mutable.value.retryAtMillis ?: return 0L
        return (retryAt - clock.nowMillis()).coerceAtLeast(0L)
    }

    private fun clearMisconfigured(): UnlockResult {
        mutable.update {
            it.copy(enabled = false, locked = false, credential = null, failedAttempts = 0, retryAtMillis = null)
        }
        return UnlockResult.Misconfigured
    }
}

/**
 * Spacing of the retries after wrong secrets: the first three are free (typing a PIN goes wrong),
 * then 5 seconds, 15, a minute and five minutes, held at five minutes after that.
 */
object LockBackoff {
    private val DELAYS_MS = longArrayOf(0L, 0L, 0L, 5_000L, 15_000L, 60_000L, 300_000L)

    fun retryDelayMillis(failedAttempts: Int): Long =
        DELAYS_MS.getOrElse((failedAttempts - 1).coerceAtLeast(0)) { DELAYS_MS.last() }
}
