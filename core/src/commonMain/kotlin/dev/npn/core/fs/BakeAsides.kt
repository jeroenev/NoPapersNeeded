package dev.npn.core.fs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The recovery names ([NpnPaths.Temp.recoveryPath]) under which this session's bakes in place keep a
 * document's original while they swap in its baked bytes. The original there is its bake's to put
 * back or delete. A rescan that lists it meanwhile sees the document under that name, and the path
 * settle that would then move it on to the document's intended path ([DocumentPaths.settle]) leaves it
 * alone: taken from under the bake, the original would be at neither of the places its bake's
 * restore looks.
 */
internal class BakeAsides {
    private val held = MutableStateFlow<Set<String>>(emptySet())

    /** Whether a bake in progress keeps its original at [relPath]. */
    fun holds(relPath: String): Boolean = relPath in held.value

    /** Run [block], the swap of a bake that keeps its original at [relPath], with [relPath] held. */
    suspend fun <T> holding(
        relPath: String,
        block: suspend () -> T,
    ): T {
        held.update { it + relPath }
        try {
            return block()
        } finally {
            held.update { it - relPath }
        }
    }
}
