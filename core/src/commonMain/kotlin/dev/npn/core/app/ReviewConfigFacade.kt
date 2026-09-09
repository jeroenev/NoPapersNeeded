package dev.npn.core.app

import dev.npn.core.model.ReviewMode
import dev.npn.core.model.StandardField
import kotlinx.coroutines.flow.Flow

/** Library-wide review workflow and standard-field visibility, synced via the journal. */
interface ReviewConfigFacade {
    /** Library-wide review workflow, synced across devices via the journal. */
    val reviewMode: Flow<ReviewMode>

    suspend fun setReviewMode(mode: ReviewMode)

    /**
     * Library-wide set of visible standard document fields (correspondent, type, date, notes),
     * synced across devices via the journal. Hiding a field only hides its editor; stored values
     * survive and reappear when re-enabled.
     */
    val standardFields: Flow<Set<StandardField>>

    suspend fun setStandardFields(fields: Set<StandardField>)

    /**
     * One-shot migration used when switching to INBOX_WORKFLOW. Marks documents reviewed:
     * [onlyTagged] true -> only documents that currently have at least one tag; false -> all live
     * documents. Writes reviewed=true prop-set events only where needed (unaffected docs keep the
     * default false). No-op set writes nothing.
     */
    suspend fun markReviewedForMigration(onlyTagged: Boolean)
}
