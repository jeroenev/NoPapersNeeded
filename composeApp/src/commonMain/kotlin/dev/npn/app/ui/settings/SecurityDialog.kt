package dev.npn.app.ui.settings

import dev.npn.core.security.LockTimeout
import dev.npn.core.security.UnlockResult
import kotlinx.serialization.Serializable

/**
 * The app-lock dialog Settings has open, or none. [SetPin] is offered when the lock is turned on,
 * [ConfirmCurrent] before a lock that is on is changed, and [ChangePin] once that check passed for
 * a new PIN. The settings viewmodel keeps it, so a recreated screen or a change of language leaves
 * it open; what is typed into it is the dialog's own unsaved `remember` buffer.
 */
@Serializable
internal sealed interface SecurityDialog {
    @Serializable
    data object SetPin : SecurityDialog

    @Serializable
    data object ChangePin : SecurityDialog

    /** Asks for the current secret (or the device's own unlock) before [change] is made. */
    @Serializable
    data class ConfirmCurrent(
        val change: LockChange,
    ) : SecurityDialog
}

/**
 * A change to a lock that is on, which only someone who knows its secret may make: whoever picks up
 * the unlocked app must not be able to turn the lock off or set a secret of their own.
 */
@Serializable
internal sealed interface LockChange {
    @Serializable
    data object TurnOff : LockChange

    @Serializable
    data object ChangePin : LockChange

    @Serializable
    data class Timeout(
        val timeout: LockTimeout,
    ) : LockChange
}

/** The current secret's check in [SecurityDialog.ConfirmCurrent]: running, or why it failed. */
internal data class SecurityCheck(
    val checking: Boolean = false,
    /** The last attempt's verdict, while it failed. */
    val outcome: UnlockResult? = null,
)
