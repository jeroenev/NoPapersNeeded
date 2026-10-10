package dev.npn.core.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session's lock on in-place bakes. A bake holds it from its read of the document to its relink,
 * and a trash or a delete for good holds it too, so each waits for a bake in progress and then acts
 * on the document the bake left ([JournalCoordinator.changeLifecycle] follows the relink). Landing
 * between a bake's swap and its relink, a trash would be recorded against bytes no longer there, and
 * the baked document would come back live.
 *
 * Lock order: this lock, then the coordinator's. Nothing takes it while holding the coordinator's
 * lock, the storage-path applicator's or the rule applier's, so a bake may take those under it.
 */
internal class BakeLock {
    private val mutex = Mutex()

    suspend fun <T> held(block: suspend () -> T): T = mutex.withLock { block() }
}
