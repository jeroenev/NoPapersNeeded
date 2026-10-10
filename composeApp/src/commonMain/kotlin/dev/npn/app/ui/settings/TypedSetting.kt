package dev.npn.app.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long typing into a setting must pause before what was typed is saved. */
private const val TYPING_PAUSE_MS = 800L

/**
 * A setting typed into a field, whose every save is a write worth sparing (a journal event every
 * replica keeps, a settings file written out whole). Each keystroke shows at once, as the [draft];
 * the value is saved once typing pauses, or when [scope] ends: leaving the screen
 * still saves, NonCancellable, so the leaving cannot cut the save short. A draft stays until its save
 * has landed, so one whose save failed stays on screen as typed, for the next edit to save.
 *
 * [save] says whether the value landed; it reports its own failure.
 */
internal class TypedSetting<T : Any>(
    private val scope: CoroutineScope,
    private val save: suspend (T) -> Boolean,
) {
    private val typed = MutableStateFlow<T?>(null)

    /** What was typed and not saved yet, or null when the field shows the saved value. */
    val draft: StateFlow<T?> = typed.asStateFlow()

    private var pending: Job? = null

    fun type(value: T) {
        typed.value = value
        pending?.cancel()
        pending =
            scope.launch {
                try {
                    delay(TYPING_PAUSE_MS)
                } finally {
                    // Not when a later keystroke replaced it; otherwise even when the pause was cut short.
                    if (typed.value == value) withContext(NonCancellable) { if (save(value)) saved(value) }
                }
            }
    }

    /** [value] was saved another way: it is no draft any more, unless typing went on since. */
    fun saved(value: T) {
        typed.compareAndSet(value, null)
    }
}
