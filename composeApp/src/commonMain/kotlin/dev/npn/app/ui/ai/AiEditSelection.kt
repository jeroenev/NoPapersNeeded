package dev.npn.app.ui.ai

/**
 * Hand-off of the library's selected documents to the AI-edit page.
 *
 * Deliberately not a navigation argument: a selection can hold thousands of 64-char document ids,
 * which have no business being serialized into a route. Set right before navigating and read once
 * when the page's viewmodel is created; a lost hand-off (process death) simply falls back to the
 * filter-based scopes.
 */
class AiEditSelection {
    private var pending: List<String> = emptyList()

    fun set(docIds: Collection<String>) {
        pending = docIds.toList()
    }

    /** Reads and clears the pending hand-off. */
    fun consume(): List<String> = pending.also { pending = emptyList() }
}
