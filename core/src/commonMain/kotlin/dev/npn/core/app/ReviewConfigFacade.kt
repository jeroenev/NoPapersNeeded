package dev.npn.core.app

import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.flow.Flow

/** Library-wide review workflow, synced via the journal. */
interface ReviewConfigFacade {
    /** Library-wide review workflow, synced across devices via the journal. */
    val reviewMode: Flow<ReviewMode>

    suspend fun setReviewMode(mode: ReviewMode)

    /**
     * One-shot migration used when switching to INBOX_WORKFLOW. Marks documents reviewed:
     * [onlyTagged] true -> only documents that currently have at least one tag; false -> all live
     * documents. Writes reviewed=true prop-set events only where needed (unaffected docs keep the
     * default false). No-op set writes nothing.
     */
    suspend fun markReviewedForMigration(onlyTagged: Boolean)
}
