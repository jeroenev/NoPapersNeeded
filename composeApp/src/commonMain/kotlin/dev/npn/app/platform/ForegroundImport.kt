package dev.npn.app.platform

import dev.npn.app.di.AppEnvironment
import dev.npn.core.app.LibrarySession

/**
 * Run a platform import against a ready library and keep the caller alive until the work directly
 * caused by that import has drained. Cold Android share intents can arrive before Compose starts
 * persisted-library restoration, so this boundary performs restoration itself when necessary.
 */
suspend fun <T> AppEnvironment.withLibraryForForegroundImport(import: suspend (LibrarySession) -> T): T? {
    val library =
        session.value ?: run {
            openPersistedLibrary()
            session.value
        } ?: return null
    val result = import(library)
    library.work.drainOcr()
    library.work.drainEnrichment()
    return result
}
