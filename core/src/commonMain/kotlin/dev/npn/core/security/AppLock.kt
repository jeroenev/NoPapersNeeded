package dev.npn.core.security

import dev.npn.core.util.MonotonicClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/**
 * What the shell and the lock screen show. It deliberately carries only what they act on: the
 * interaction timestamps that drive the desktop idle timer stay inside [AppLock], so a mouse move
 * does not recompose the app shell.
 */
data class AppLockState(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val timeout: LockTimeout = LockTimeout.IMMEDIATELY,
    val failedAttempts: Int = 0,
    /** When a retry is allowed again on the monotonic clock, or null while attempts are accepted. */
    val retryAtMillis: Long? = null,
)

/** How an unlock attempt ended. */
sealed interface UnlockResult {
    /** The secret matched; the app is unlocked. */
    data object Success : UnlockResult

    /** The secret did not match. [TooManyAttempts] carries a wait, if one applies. */
    data object WrongSecret : UnlockResult

    /** Attempts are held back for [remainingMillis] after repeated failures. */
    data class TooManyAttempts(
        val remainingMillis: Long,
    ) : UnlockResult

    /** No lock is configured, so nothing was checked. */
    data object Disabled : UnlockResult

    /** The lock was enabled with a credential this build cannot verify; it stays locked. */
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
 * Transitions go through one compare-and-set loop, so the lock is safe to read from the shell on
 * another thread while the secret is verified off the main thread, and the published [state]
 * changes only when something the shell renders does.
 */
class AppLock(
    initial: AppLockSettings,
    private val clock: MonotonicClock,
    private val verify: (String, LockCredential) -> Boolean = LockCrypto::verify,
) {
    private val normalised = initial.normalised()
    private val phase =
        MutableStateFlow(
            Phase(
                settings = normalised,
                locked = normalised.enabled,
                lastInteractionMillis = clock.elapsedMillis(),
            ),
        )
    private val view = MutableStateFlow(phase.value.view())

    val state: StateFlow<AppLockState> = view.asStateFlow()

    /**
     * Follow the saved setting. Turning the lock on, changing its secret or choosing another
     * timeout keeps the app open: the change was made from inside the unlocked app. Turning it off
     * clears the hash and every failed attempt.
     */
    fun updateSettings(settings: AppLockSettings) {
        val next = settings.normalised()
        transition { current ->
            when {
                !next.enabled -> {
                    current.copy(
                        settings = next,
                        locked = false,
                        failedAttempts = 0,
                        retryAtMillis = null,
                        backgroundAtMillis = null,
                    )
                }

                current.settings.credential != next.credential -> {
                    current.copy(
                        settings = next,
                        locked = false,
                        failedAttempts = 0,
                        retryAtMillis = null,
                        backgroundAtMillis = null,
                        lastInteractionMillis = clock.elapsedMillis(),
                    )
                }

                else -> {
                    current.copy(settings = next)
                }
            }
        }
    }

    /** The app left the foreground (Android) or was minimized (desktop). */
    fun onBackground() {
        transition { it.copy(backgroundAtMillis = clock.elapsedMillis()) }
    }

    /** The app returned; locks once it was away for at least the chosen interval. */
    fun onForeground() {
        val now = clock.elapsedMillis()
        transition { current ->
            val awaySince = current.backgroundAtMillis
            val away = awaySince?.let { now - it }
            // A clock that moved backwards must not shorten the timeout: treat it as long enough.
            val lock =
                current.settings.enabled &&
                    !current.locked &&
                    away != null &&
                    (away < 0 || away >= current.settings.timeout.millis)
            current.copy(
                locked = current.locked || lock,
                backgroundAtMillis = null,
                lastInteractionMillis = if (lock) now else current.lastInteractionMillis,
            )
        }
    }

    /** The user did something (desktop input); the idle timer starts over. */
    fun onInteraction() {
        transition { it.copy(lastInteractionMillis = clock.elapsedMillis()) }
    }

    /**
     * The desktop idle check: locks after [AppLockState.timeout] without input.
     * [LockTimeout.IMMEDIATELY] adds nothing here, since locking the instant the user touches
     * nothing would make the app unusable; minimizing is its trigger instead ([onBackground]).
     */
    fun checkIdle() {
        val now = clock.elapsedMillis()
        transition { current ->
            val sinceInput = now - current.lastInteractionMillis
            val idle =
                current.settings.enabled &&
                    !current.locked &&
                    current.settings.timeout.millis > 0 &&
                    (sinceInput < 0 || sinceInput >= current.settings.timeout.millis)
            if (idle) current.copy(locked = true) else current
        }
    }

    /** Lock now, for an explicit lock action; a disabled lock stays open. */
    fun lockNow() {
        transition { if (it.settings.enabled) it.copy(locked = true) else it }
    }

    /**
     * Check [secret] against the saved hash. Repeated wrong secrets hold the next attempt back
     * ([LockBackoff]), which slows guessing at the lock screen. The counter lives in this process
     * only: a cold start begins locked again with a fresh count. That is deliberate for a lock over
     * plain files, which is not a defence against someone who can read the folder directly.
     */
    fun unlockWithSecret(secret: String): UnlockResult {
        val now = clock.elapsedMillis()
        val before = phase.value
        if (!before.settings.enabled) return UnlockResult.Disabled
        before.retryAtMillis?.let { retryAt -> if (now < retryAt) return UnlockResult.TooManyAttempts(retryAt - now) }
        val credential = before.settings.credential ?: return clearMisconfigured()
        val correct = verify(secret, credential)

        var outcome: UnlockResult = UnlockResult.WrongSecret
        transition { current ->
            val retryAt = current.retryAtMillis
            when {
                !current.settings.enabled -> {
                    outcome = UnlockResult.Disabled
                    current.copy(locked = false)
                }

                // The setting changed while the slow hash ran: the stored setting wins, and the app
                // stays locked rather than trusting an attempt judged against the old hash.
                current.settings.credential != credential -> {
                    outcome = UnlockResult.Misconfigured
                    current.copy(locked = true, failedAttempts = 0, retryAtMillis = null)
                }

                retryAt != null && retryAt > now -> {
                    outcome = UnlockResult.TooManyAttempts(retryAt - now)
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
        transition { current ->
            if (!current.settings.enabled) {
                current
            } else {
                current.copy(
                    locked = false,
                    failedAttempts = 0,
                    retryAtMillis = null,
                    backgroundAtMillis = null,
                    lastInteractionMillis = clock.elapsedMillis(),
                )
            }
        }
    }

    /** How long until another attempt is accepted; 0 when one is accepted now. */
    fun retryRemainingMillis(): Long {
        val retryAt = phase.value.retryAtMillis ?: return 0L
        // A forward clock jump must not turn a five-minute wait into hours.
        return (retryAt - clock.elapsedMillis()).coerceIn(0L, LockBackoff.MAX_DELAY_MS)
    }

    private fun clearMisconfigured(): UnlockResult {
        transition {
            it.copy(
                settings = it.settings.copy(enabled = false, credential = null),
                locked = false,
                failedAttempts = 0,
                retryAtMillis = null,
            )
        }
        return UnlockResult.Misconfigured
    }

    /** Apply [transform] atomically and publish the renderable state when it changed. */
    private fun transition(transform: (Phase) -> Phase) {
        while (true) {
            val current = phase.value
            val next = transform(current)
            if (phase.compareAndSet(current, next)) {
                val rendered = next.view()
                if (view.value != rendered) view.value = rendered
                return
            }
        }
    }

    private data class Phase(
        val settings: AppLockSettings,
        val locked: Boolean,
        val failedAttempts: Int = 0,
        val retryAtMillis: Long? = null,
        val backgroundAtMillis: Long? = null,
        val lastInteractionMillis: Long,
    ) {
        fun view(): AppLockState =
            AppLockState(
                enabled = settings.enabled,
                locked = locked,
                timeout = settings.timeout,
                failedAttempts = failedAttempts,
                retryAtMillis = retryAtMillis,
            )
    }
}

/**
 * Spacing of the retries after wrong secrets: the first three are free (typing a PIN goes wrong),
 * then 5 seconds, 15, a minute and five minutes, held at five minutes after that.
 */
object LockBackoff {
    /** The longest wait, and the cap a forward clock jump is clamped to. */
    const val MAX_DELAY_MS = 300_000L

    private val DELAYS_MS = longArrayOf(0L, 0L, 0L, 5_000L, 15_000L, 60_000L, MAX_DELAY_MS)

    fun retryDelayMillis(failedAttempts: Int): Long =
        DELAYS_MS.getOrElse((failedAttempts - 1).coerceAtLeast(0)) { MAX_DELAY_MS }
}
