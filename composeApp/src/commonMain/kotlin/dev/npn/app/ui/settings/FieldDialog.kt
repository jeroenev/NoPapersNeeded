package dev.npn.app.ui.settings

import dev.npn.app.ui.common.FieldDefFormState
import kotlinx.serialization.Serializable

/**
 * The one dialog the field manager has open, or none, with what is typed or ticked in it. Edit and
 * Delete name their field by key and are resolved against the current registry, so a field edited on
 * another device meanwhile shows as it is now. The settings viewmodel keeps it
 * ([SettingsViewModel.fieldDialog]), so it is open again, as it was, after the screen is recreated
 * or the window changes width.
 */
@Serializable
internal sealed interface FieldDialog {
    /** Define a new field, with its [form] as typed so far. */
    @Serializable
    data class Add(
        val form: FieldDefFormState = FieldDefFormState(),
    ) : FieldDialog

    /**
     * Edit the field [key], with its [form] as typed so far, and whether the type change it makes
     * is being confirmed.
     */
    @Serializable
    data class Edit(
        val key: String,
        val form: FieldDefFormState,
        val confirmingTypeChange: Boolean = false,
    ) : FieldDialog

    /** Delete the field [key], with or without its values. */
    @Serializable
    data class Delete(
        val key: String,
        val clearValues: Boolean = false,
    ) : FieldDialog
}
