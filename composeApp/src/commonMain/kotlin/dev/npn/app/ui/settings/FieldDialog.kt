package dev.npn.app.ui.settings

import kotlinx.serialization.Serializable

/**
 * The one dialog the field manager has open, or none. Edit and Delete name their field by key and
 * are resolved against the current registry, so a field edited on another device meanwhile shows as
 * it is now. The settings viewmodel keeps it ([SettingsViewModel.fieldDialog]), so it is open again
 * after the screen is recreated; what is typed or ticked into it is the dialog's own
 * (`rememberSaveable`), which keeps its place across layouts as the pane moves as one.
 */
@Serializable
internal sealed interface FieldDialog {
    /** Define a new field. */
    @Serializable
    data object Add : FieldDialog

    /** Edit the field [key]. */
    @Serializable
    data class Edit(
        val key: String,
    ) : FieldDialog

    /** Delete the field [key]. */
    @Serializable
    data class Delete(
        val key: String,
    ) : FieldDialog
}
