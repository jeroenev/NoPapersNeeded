package dev.npn.core.watch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/** Both are paths, compared as real paths ([pathLiesIn]); a string that is no path lies nowhere. */
actual suspend fun liesInLibrary(
    folder: String,
    library: String,
    io: CoroutineDispatcher,
): Boolean =
    withContext(io) {
        runCatching { pathLiesIn(Paths.get(folder), Paths.get(library)) }.getOrDefault(false)
    }
