package dev.npn.core.journal

/**
 * Persisted-locally bookkeeping for the writing device. Never lives in the library folder
 * (it is device-private): tracks the last seq this device assigned and the set of own
 * segment file names it knows it wrote, for fork detection.
 */
interface LocalJournalState {
    suspend fun getLastSeq(): Long?
    suspend fun setLastSeq(seq: Long)
    suspend fun getKnownOwnSegments(): Set<String>
    suspend fun setKnownOwnSegments(fileNames: Set<String>)
}

/** In-memory [LocalJournalState] for tests. */
class InMemoryLocalJournalState : LocalJournalState {
    private var lastSeq: Long? = null
    private var known: Set<String> = emptySet()

    override suspend fun getLastSeq(): Long? = lastSeq
    override suspend fun setLastSeq(seq: Long) { lastSeq = seq }
    override suspend fun getKnownOwnSegments(): Set<String> = known
    override suspend fun setKnownOwnSegments(fileNames: Set<String>) { known = fileNames }
}
