package dev.npn.app.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.security.AppLock
import dev.npn.core.security.AppLockState
import dev.npn.core.security.UnlockResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the lock screen renders. The typed secret itself stays in the composable, never here. */
data class LockUiState(
    val lock: AppLockState = AppLockState(),
    val checking: Boolean = false,
    /** The last attempt's verdict, shown until the user types again. */
    val outcome: UnlockResult? = null,
)

/** What the lock screen can do; [LockViewModel] implements it. */
interface LockActions {
    /** Check [secret]; a wrong one leaves the app locked and reports why. */
    fun unlock(secret: String)

    /** The platform proved the user (Android biometrics or device credential). */
    fun unlockWithDevice()

    /** How long attempt backoff still holds, for disabling the unlock button. */
    fun retryRemainingMillis(): Long
}

/**
 * Drives the lock screen. The expensive hash runs off the main thread; the app-lock state machine
 * owns the decision, so this only carries the in-flight flag and the last verdict.
 */
class LockViewModel(
    private val lock: AppLock,
    private val verifyDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel(),
    LockActions {
    private val checking = MutableStateFlow(false)
    private val outcome = MutableStateFlow<UnlockResult?>(null)

    val state: StateFlow<LockUiState> =
        combine(lock.state, checking, outcome) { lockState, inFlight, last ->
            LockUiState(lock = lockState, checking = inFlight, outcome = last)
        }.stateIn(viewModelScope, WhileSubscribedTimeout, LockUiState(lock.state.value))

    init {
        // A fresh lock starts without the previous unlock attempt's verdict on screen.
        viewModelScope.launch {
            lock.state
                .map { it.locked }
                .distinctUntilChanged()
                .collect { locked ->
                    if (locked) {
                        outcome.value = null
                        checking.value = false
                    }
                }
        }
    }

    override fun unlock(secret: String) {
        // The button and the IME "Done" can fire together; only one attempt may be in flight.
        if (secret.isEmpty() || !checking.compareAndSet(false, true)) return
        outcome.value = null
        viewModelScope.launch {
            val result = withContext(verifyDispatcher) { lock.unlockWithSecret(secret) }
            outcome.value = result.takeUnless { it == UnlockResult.Success }
            checking.value = false
        }
    }

    /** Unlock by the platform's proof; only a successful [AppLockAuthenticator] callback calls this. */
    override fun unlockWithDevice() {
        outcome.value = null
        lock.unlockWithSystem()
    }

    override fun retryRemainingMillis(): Long = lock.retryRemainingMillis()
}
