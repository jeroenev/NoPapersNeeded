package dev.npn.core.fs

/**
 * Where the app notes a multi-step operation on the library folder before its first effect, and
 * closes it after its last: one a crash cuts short is finished or undone at the next open or rescan
 * (`dev.npn.core.index.PendingIntents`). Each call returns the id [done] closes.
 */
internal interface OperationLog {
    /**
     * A trash whose event comes first, then its file at [path], of [stat], goes into the keep store
     * in the episode [episodeMillis], claimed under [aside] to be deleted.
     */
    suspend fun trashKept(
        docId: String,
        path: String,
        stat: FileMeta,
        episodeMillis: Long,
        aside: String,
    ): String

    /**
     * A bake in place of [docId] at [norm], its original moved aside to [aside] for the bytes [baked];
     * noted before its relink, so the note's time is an episode no later than the relink's.
     */
    suspend fun bakeAside(
        docId: String,
        norm: String,
        aside: String,
        baked: String,
    ): String

    /** The operation [id] ended, done or undone: nothing is left to finish. */
    suspend fun done(id: String)

    /**
     * The code running the operation [id] returned or threw, whether or not it called [done]: one
     * it left open (a failure part way) is the next rescan's to settle, as a crash's would be.
     */
    fun release(id: String)

    /** Notes nothing: what a crash cuts short is left to the rescan, as before intents. */
    companion object None : OperationLog {
        override suspend fun trashKept(
            docId: String,
            path: String,
            stat: FileMeta,
            episodeMillis: Long,
            aside: String,
        ): String = ""

        override suspend fun bakeAside(
            docId: String,
            norm: String,
            aside: String,
            baked: String,
        ): String = ""

        override suspend fun done(id: String) = Unit

        override fun release(id: String) = Unit
    }
}
