package dev.npn.app.platform

import dev.npn.app.di.AppEnvironment
import dev.npn.core.app.LibrarySession
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.first

/** What a platform import (share sheet, open with) came to, for the one message it shows. */
sealed interface ForegroundImport<out T> {
    /** No library is set up yet, so there is nowhere to import into. */
    data object NoLibrary : ForegroundImport<Nothing>

    /** The saved library could not be opened, or the import itself threw. */
    data class Failed(
        val error: Throwable,
    ) : ForegroundImport<Nothing>

    data class Done<T>(
        val result: T,
    ) : ForegroundImport<T>
}

/**
 * Run a platform import against the library, opening the saved one first when the app was started
 * just for this: a cold share intent arrives before the shell has restored anything.
 *
 * Returns as soon as the import itself is done, so the user hears back at once. The OCR and AI
 * enrichment it queued run on the session meanwhile; keeping the process alive for them is the
 * caller's to arrange.
 */
suspend fun <T> AppEnvironment.importIntoLibrary(import: suspend (LibrarySession) -> T): ForegroundImport<T> {
    val library =
        session.value ?: run {
            if (settings.settings.first().libraryLocation == null) return ForegroundImport.NoLibrary
            runCatchingCancellable { openPersistedLibrary() }.onFailure { return ForegroundImport.Failed(it) }
            session.value
        } ?: return ForegroundImport.NoLibrary
    return runCatchingCancellable { import(library) }.fold(
        onSuccess = { ForegroundImport.Done(it) },
        onFailure = { ForegroundImport.Failed(it) },
    )
}
