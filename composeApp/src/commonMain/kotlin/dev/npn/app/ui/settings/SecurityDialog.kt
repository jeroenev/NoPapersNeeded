package dev.npn.app.ui.settings

import kotlinx.serialization.Serializable

/**
 * The app-lock dialog Settings has open, or none. [SetPin] is offered when the lock is turned on,
 * [ChangePin] while it is already on. The settings viewmodel keeps it, so a recreated screen or a
 * change of language leaves it open; what is typed into it is the dialog's own unsaved `remember`
 * buffer.
 */
@Serializable
internal sealed interface SecurityDialog {
    @Serializable
    data object SetPin : SecurityDialog

    @Serializable
    data object ChangePin : SecurityDialog
}
