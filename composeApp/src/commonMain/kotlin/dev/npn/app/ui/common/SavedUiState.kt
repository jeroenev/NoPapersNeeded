package dev.npn.app.ui.common

import androidx.lifecycle.SavedStateHandle
import dev.npn.core.util.NpnJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * One piece of screen state the user would miss after the screen is recreated: a selection, text
 * typed into the screen, the pane, tab or document it shows, an open dialog and what it holds.
 *
 * Its viewmodel holds it, and so keeps it through everything a composable does not outlive: a
 * recreated window, a change of the app's language (which rebuilds the whole UI) and a change of
 * width class (which swaps the layout around it). For a restored process, every change is saved in
 * the viewmodel's [SavedStateHandle] under [key] ([saveUiState]), and the new viewmodel starts from
 * it; one that was not saved, or no longer decodes, starts from [initial].
 *
 * Changed on the main thread only, as a viewmodel's UI state is.
 */
class SavedUiState<T>(
    private val handle: SavedStateHandle,
    private val key: String,
    private val serializer: KSerializer<T>,
    initial: T,
) {
    private val state = MutableStateFlow(handle.restored(key, serializer, initial))

    val flow: StateFlow<T> = state.asStateFlow()

    val value: T get() = state.value

    fun set(value: T) {
        state.value = value
        handle.saveUiState(key, serializer, value)
    }

    fun update(transform: (T) -> T) = handle.saveUiState(key, serializer, state.updateAndGet(transform))

    companion object {
        /** The longest JSON one value saves; about 40 KB in a parcel. */
        const val MAX_SAVED_CHARS = 20_000
    }
}

/** A [SavedUiState] of [T] under [key], starting from [initial] unless a restored process saved one. */
inline fun <reified T> SavedStateHandle.uiState(
    key: String,
    initial: T,
): SavedUiState<T> = SavedUiState(this, key, serializer<T>(), initial)

/**
 * Save [value] under [key] for a restored process, as its [NpnJson.Persisted] JSON. A value longer
 * than [SavedUiState.MAX_SAVED_CHARS] is not saved, and drops what was (Android's saved state is
 * small, and a selection can span the library): the restored screen starts without it.
 *
 * For a viewmodel that keeps some of its screen state in a state of its own, changed in many places;
 * one value on its own is a [SavedUiState].
 */
fun <T> SavedStateHandle.saveUiState(
    key: String,
    serializer: KSerializer<T>,
    value: T,
) {
    val json = NpnJson.Persisted.encodeToString(serializer, value)
    if (json.length <= SavedUiState.MAX_SAVED_CHARS) set(key, json) else remove<String>(key)
}

/**
 * Whether [saveUiState] saves [value] rather than dropping it: its JSON is at most
 * [SavedUiState.MAX_SAVED_CHARS] long. For a value that must never be dropped, which saves a smaller
 * stand-in when it does not fit.
 */
fun <T> fitsUiState(
    serializer: KSerializer<T>,
    value: T,
): Boolean = NpnJson.Persisted.encodeToString(serializer, value).length <= SavedUiState.MAX_SAVED_CHARS

/** What [saveUiState] saved under [key], or [initial] when nothing was, or it no longer decodes. */
fun <T> SavedStateHandle.restored(
    key: String,
    serializer: KSerializer<T>,
    initial: T,
): T {
    val saved = get<String>(key) ?: return initial
    return try {
        NpnJson.Persisted.decodeFromString(serializer, saved)
    } catch (_: IllegalArgumentException) {
        // A SerializationException, or a value its class's checks refuse.
        initial
    }
}
