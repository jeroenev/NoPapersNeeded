package dev.npn.core.fs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The document paths an in-place bake of this session has freed for a moment: the bake moves the
 * original aside before it moves the baked bytes in ([dev.npn.core.xmp.BakeService.bakeInPlace]).
 * A rescan in that moment sees the original under its recovery name as the document's move and
 * lets its path go, and the app's own path moves ([DocumentPaths]) keep out of such a path, as
 * they would of a taken one: a document whose intent names it would otherwise take the name, and
 * the bake could not put either version back. Another process or a sync tool can still take it;
 * the bake then keeps the original under its recovery name.
 */
internal class BakingPaths {
    private val paths = MutableStateFlow<Set<String>>(emptySet())

    operator fun contains(path: String): Boolean = path in paths.value

    /** Run [block], the swap of a bake of [path], with [path] held. */
    suspend fun <T> holding(
        path: String,
        block: suspend () -> T,
    ): T {
        paths.update { it + path }
        try {
            return block()
        } finally {
            paths.update { it - path }
        }
    }
}
