package dev.npn.core.security

import dev.npn.core.util.MonotonicClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * How long the app may stay in the background before it asks for the unlock secret again.
 *
 * The intervals are the choices Settings offers, as far as the platform offers them ([choices]).
 * [IMMEDIATELY] locks as soon as the app returns from the background; the others allow that much
 * time away. On desktop, where there is no background to rely on, the app treats a minimized window
 * as the background and also locks after the chosen interval without input, so [IMMEDIATELY] is not
 * offered there.
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
        /** The intervals this platform offers, in order. */
        val choices: List<LockTimeout> get() = platformLockTimeouts

        /** What a new lock uses, and what an interval this platform does not offer reads back as. */
        val DEFAULT: LockTimeout get() = platformDefaultLockTimeout

        /** A stored name, read back as [DEFAULT] when it is absent, unknown or not offered here. */
        fun fromWire(value: String?): LockTimeout =
            entries.firstOrNull { it.name == value }?.takeIf { it in choices } ?: DEFAULT
    }
}

/** The intervals this platform's Settings offers ([LockTimeout.choices]). */
internal expect val platformLockTimeouts: List<LockTimeout>

/** This platform's [LockTimeout.DEFAULT]; always one of [platformLockTimeouts]. */
internal expect val platformDefaultLockTimeout: LockTimeout

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
    val timeout: LockTimeout = LockTimeout.DEFAULT,
    val credential: LockCredential? = null,
    /**
     * The secret is digits only, so the screens that ask for it offer a number pad. Only a hint for
     * the keyboard: whoever sees that keyboard learns as much.
     */
    val numeric: Boolean = false,
) {
    /**
     * The setting as it may be stored: an enabled lock needs a credential this build can verify.
     * A hash that went missing or does not read disables the lock rather than leaving a door no
     * secret opens, and the credential is dropped while the lock is off. An interval this platform
     * does not offer becomes its default.
     */
    fun normalised(): AppLockSettings {
        val usable = credential?.takeIf { it.isValid() }
        val offered = timeout.takeIf { it in LockTimeout.choices } ?: LockTimeout.DEFAULT
        return if (enabled && usable != null) {
            copy(credential = usable, timeout = offered)
        } else {
            copy(enabled = false, credential = null, numeric = false, timeout = offered)
        }
    }

    companion object {
        /** Whether [secret] is one the lock offers a number pad for ([numeric]). */
        fun isNumeric(secret: String): Boolean = secret.isNotEmpty() && secret.all { it in '0'..'9' }
    }
}

/**
 * The wrong secrets this device has seen since its last unlock, kept beside the lock's setting so a
 * restart does not hand out a fresh set of free attempts. [retryAtMillis] is on the lock's
 * [MonotonicClock]; a value read back after a reboot (Android's clock starts over) or a clock change
 * is held to the wait its [failures] earn, so it can lengthen a wait by no more than that wait.
 */
data class LockAttempts(
    val failures: Int = 0,
    val retryAtMillis: Long? = null,
)

/**
 * What the shell and the lock screen show. It deliberately carries only what they act on: the
 * interaction timestamps that drive the desktop idle timer stay inside [AppLock], so a mouse move
 * does not recompose the app shell.
 */
data class AppLockState(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val timeout: LockTimeout = LockTimeout.DEFAULT,
    /** The secret is digits only ([AppLockSettings.numeric]). */
    val numeric: Boolean = false,
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
}

/**
 * The app-lock decision state: one machine for the shell, the lock screen and tests, with the clock
 * injected so "one minute in the background" is a fact about the clock rather than the test runner.
 *
 * The lock engages when the app starts (a cold start always shows it) and when the app returns
 * from the background after [AppLockState.timeout], or after [onBackground]'s grace when that is
 * longer. [onInteraction] and [checkIdle] add the desktop idle rule. Disabling the lock, or setting
 * a new secret, unlocks and resets attempts: those happen while the user is in the app, after
 * Settings asked for the current secret.
 *
 * Wrong secrets are counted in [attempts], which the shell stores with the setting and hands back to
 * the next process, so the backoff outlives a restart.
 *
 * Transitions go through one compare-and-set loop, so the lock is safe to read from the shell on
 * another thread while the secret is verified off the main thread, and the published [state]
 * changes only when something the shell renders does.
 */
class AppLock(
    initial: AppLockSettings,
    private val clock: MonotonicClock,
    private val verify: (String, LockCredential) -> Boolean = LockCrypto::verify,
    attempts: LockAttempts = LockAttempts(),
) {
    private val phase = MutableStateFlow(startPhase(initial.normalised(), attempts, clock.elapsedMillis()))
    private val view = MutableStateFlow(phase.value.view())
    private val counted = MutableStateFlow(phase.value.attempts())

    val state: StateFlow<AppLockState> = view.asStateFlow()

    /** The wrong secrets counted since the last unlock, for the shell to store ([LockAttempts]). */
    val attempts: StateFlow<LockAttempts> = counted.asStateFlow()

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

    /**
     * The app left the foreground (Android) or was minimized (desktop). The time away counts from
     * now; the lock engages on return once it reaches the timeout, or [graceMillis] when that is
     * longer: a hand-off to another app the user is expected to come back from (a picker, the share
     * sheet) is given that much, bounded, rather than stopping the clock.
     */
    fun onBackground(graceMillis: Long = 0L) {
        val now = clock.elapsedMillis()
        transition { it.copy(backgroundAtMillis = now, backgroundGraceMillis = graceMillis.coerceAtLeast(0L)) }
    }

    /**
     * The screen went off. Time already away from the app counts against the plain timeout from
     * here on: whatever the app was handed off to, the user is no longer in it.
     */
    fun onScreenOff() {
        transition { if (it.backgroundAtMillis != null) it.copy(backgroundGraceMillis = 0L) else it }
    }

    /** The app returned; locks once it was away for at least the chosen interval (or grace). */
    fun onForeground() {
        val now = clock.elapsedMillis()
        transition { current ->
            val awaySince = current.backgroundAtMillis
            val away = awaySince?.let { now - it }
            val allowed = maxOf(current.settings.timeout.millis, current.backgroundGraceMillis)
            // A clock that moved backwards must not shorten the timeout: treat it as long enough.
            val lock =
                current.settings.enabled &&
                    !current.locked &&
                    away != null &&
                    (away < 0 || away >= allowed)
            current.copy(
                locked = current.locked || lock,
                backgroundAtMillis = null,
                backgroundGraceMillis = 0L,
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
     * [LockTimeout.IMMEDIATELY] (not offered on desktop) adds nothing here, since locking the
     * instant the user touches nothing would make the app unusable.
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

    /** Suspends until the app is unlocked (or the lock is off); returns at once when it already is. */
    suspend fun awaitUnlocked() {
        state.first { !it.locked }
    }

    /**
     * Check [secret] against the saved hash: the lock screen's unlock, and Settings' check for the
     * current secret before the lock is changed. Repeated wrong secrets hold the next attempt back
     * ([LockBackoff]), which slows guessing; the count is in [attempts], so it outlives a restart.
     * The lock is not a defence against someone who can read the folder directly.
     */
    fun unlockWithSecret(secret: String): UnlockResult {
        var outcome: UnlockResult? = null
        // A credential changed while the slow hash ran is checked again: the stored setting wins.
        while (outcome == null) outcome = attempt(secret)
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
        val now = clock.elapsedMillis()
        transition { it.heldTo(now) }
        val retryAt = phase.value.retryAtMillis ?: return 0L
        return (retryAt - now).coerceAtLeast(0L)
    }

    /** One check of [secret], or null when the credential changed under it and it must run again. */
    private fun attempt(secret: String): UnlockResult? {
        val started = clock.elapsedMillis()
        transition { it.heldTo(started) }
        val before = phase.value
        if (!before.settings.enabled) return UnlockResult.Disabled
        before.retryAtMillis?.let { retryAt ->
            if (started <
                retryAt
            ) {
                return UnlockResult.TooManyAttempts(retryAt - started)
            }
        }
        val credential = checkNotNull(before.settings.credential) { "An enabled lock always has a credential" }
        val correct = verify(secret, credential)
        val now = clock.elapsedMillis()

        var outcome: UnlockResult? = null
        transition { current ->
            val retryAt = current.retryAtMillis
            when {
                !current.settings.enabled -> {
                    outcome = UnlockResult.Disabled
                    current.copy(locked = false)
                }

                current.settings.credential != credential -> {
                    outcome = null
                    current
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

    /** Apply [transform] atomically and publish what changed of the renderable and stored state. */
    private fun transition(transform: (Phase) -> Phase) {
        while (true) {
            val current = phase.value
            val next = transform(current)
            if (phase.compareAndSet(current, next)) {
                val rendered = next.view()
                if (view.value != rendered) view.value = rendered
                val stored = next.attempts()
                if (counted.value != stored) counted.value = stored
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
        val backgroundGraceMillis: Long = 0L,
        val lastInteractionMillis: Long,
    ) {
        fun view(): AppLockState =
            AppLockState(
                enabled = settings.enabled,
                locked = locked,
                timeout = settings.timeout,
                numeric = settings.numeric,
                failedAttempts = failedAttempts,
                retryAtMillis = retryAtMillis,
            )

        fun attempts(): LockAttempts = LockAttempts(failedAttempts, retryAtMillis)

        /**
         * The wait as it may stand at [now]: never longer than the one [failedAttempts] earn. A clock
         * that moved backwards (the desktop's is the wall clock) or started over (an Android reboot,
         * for a stored wait) would otherwise hold attempts back for as long as it jumped; the wait
         * restarts from [now] instead, as long as it was meant to be.
         */
        fun heldTo(now: Long): Phase {
            val retryAt = retryAtMillis ?: return this
            val wait = LockBackoff.retryDelayMillis(failedAttempts)
            return if (retryAt - now > wait) copy(retryAtMillis = now + wait) else this
        }
    }

    private companion object {
        fun startPhase(
            settings: AppLockSettings,
            attempts: LockAttempts,
            now: Long,
        ): Phase {
            val counted = settings.enabled && attempts.failures > 0
            return Phase(
                settings = settings,
                locked = settings.enabled,
                failedAttempts = if (counted) attempts.failures else 0,
                retryAtMillis = if (counted) attempts.retryAtMillis?.takeIf { it > now } else null,
                lastInteractionMillis = now,
            ).heldTo(now)
        }
    }
}

/**
 * Spacing of the retries after wrong secrets: the first three are free (typing a PIN goes wrong),
 * then 5 seconds, 15, a minute and five minutes, held at five minutes after that.
 */
object LockBackoff {
    /** The longest wait. */
    const val MAX_DELAY_MS = 300_000L

    private val DELAYS_MS = longArrayOf(0L, 0L, 0L, 5_000L, 15_000L, 60_000L, MAX_DELAY_MS)

    fun retryDelayMillis(failedAttempts: Int): Long =
        DELAYS_MS.getOrElse((failedAttempts - 1).coerceAtLeast(0)) { MAX_DELAY_MS }
}
