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

    override fun unlock(secret: String) {
        if (secret.isEmpty() || checking.value) return
        checking.value = true
        outcome.value = null
        viewModelScope.launch {
            val result = withContext(verifyDispatcher) { lock.unlockWithSecret(secret) }
            // Keep a wrong secret on screen to be corrected; clear the field only once it opened.
            outcome.value = result.takeUnless { it == UnlockResult.Success }
            checking.value = false
        }
    }

    override fun unlockWithDevice() {
        outcome.value = null
        lock.unlockWithSystem()
    }

    override fun retryRemainingMillis(): Long = lock.retryRemainingMillis()
}
