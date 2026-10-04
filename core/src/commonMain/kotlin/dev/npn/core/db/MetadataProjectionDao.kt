package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

class MetadataIdentityCollision(
    message: String,
) : IllegalStateException(message)

/** One stored event of the source at [sourcePath], as its canonical JSON. */
data class SourceEventJson(
    val sourcePath: String,
    val canonicalJson: String,
)

private fun validateActor(
    existing: MetadataTransactionActorEntity?,
    incoming: MetadataTransactionActorEntity,
) {
    if (existing != null && existing.objectHash != incoming.objectHash) {
        throw MetadataIdentityCollision("Transaction actor ${incoming.actorId} belongs to multiple objects")
    }
}

private fun validateIdentity(
    existing: MetadataEventEntity?,
    incoming: MetadataEventEntity,
) {
    if (existing != null && existing.canonicalJson != incoming.canonicalJson) {
        throw MetadataIdentityCollision(
            "Event identity ${incoming.deviceId}:${incoming.sequence} has conflicting content",
        )
    }
}

private fun validateHlc(
    existing: MetadataEventEntity?,
    incoming: MetadataEventEntity,
) {
    val sameIdentity =
        existing != null &&
            existing.deviceId == incoming.deviceId && existing.sequence == incoming.sequence
    if (existing != null && !sameIdentity) {
        throw MetadataIdentityCollision(
            "Distinct events share HLC ${incoming.hlcPhysical}:${incoming.hlcCounter}:${incoming.hlcDevice}",
        )
    }
}

@Dao
@Suppress("TooManyFunctions")
interface MetadataProjectionDao {
    @Query("SELECT * FROM metadata_events WHERE deviceId = :deviceId AND sequence = :sequence")
    suspend fun event(
        deviceId: String,
        sequence: Long,
    ): MetadataEventEntity?

    @Query(
        "SELECT * FROM metadata_events WHERE hlcPhysical = :physical AND hlcCounter = :counter " +
            "AND hlcDevice = :device LIMIT 1",
    )
    suspend fun eventAtHlc(
        physical: Long,
        counter: Int,
        device: String,
    ): MetadataEventEntity?

    @Query("SELECT * FROM metadata_events ORDER BY hlcPhysical, hlcCounter, hlcDevice")
    suspend fun allEvents(): List<MetadataEventEntity>

    /**
     * Events carrying one operation kind, ordered by HLC (causal order). The open path uses this
     * to fold just the library settings — the only slice of the folded state the read path needs —
     * without replaying every event ever recorded. Pass [JournalOp.SettingSet.WIRE] et al.
     */
    @Query(
        "SELECT * FROM metadata_events WHERE operation = :operation " +
            "ORDER BY hlcPhysical, hlcCounter, hlcDevice",
    )
    suspend fun eventsForOperation(operation: String): List<MetadataEventEntity>

    /** All events scoped to [documentId], ordered by HLC (causal order). */
    @Query(
        "SELECT * FROM metadata_events WHERE documentId = :documentId " +
            "ORDER BY hlcPhysical, hlcCounter, hlcDevice",
    )
    suspend fun eventsForDocument(documentId: String): List<MetadataEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: MetadataEventEntity): Long

    @Query("SELECT * FROM metadata_sources WHERE path = :path")
    suspend fun source(path: String): MetadataSourceEntity?

    @Query("SELECT * FROM metadata_sources ORDER BY path")
    suspend fun allSources(): List<MetadataSourceEntity>

    @Upsert
    suspend fun upsertSource(source: MetadataSourceEntity)

    @Query("DELETE FROM metadata_source_events WHERE sourcePath = :sourcePath")
    suspend fun deleteSourceEvents(sourcePath: String)

    /** Delete one event if no source references it any more. Both lookups are by key. */
    @Query(
        "DELETE FROM metadata_events WHERE deviceId = :deviceId AND sequence = :sequence AND NOT EXISTS (" +
            "SELECT 1 FROM metadata_source_events WHERE eventDeviceId = :deviceId AND eventSequence = :sequence)",
    )
    suspend fun deleteEventIfUnreferenced(
        deviceId: String,
        sequence: Long,
    )

    @Query("SELECT * FROM metadata_source_events WHERE sourcePath = :sourcePath")
    suspend fun sourceEvents(sourcePath: String): List<MetadataSourceEventEntity>

    @Query(
        "SELECT se.sourcePath AS sourcePath, e.canonicalJson AS canonicalJson FROM metadata_source_events se " +
            "JOIN metadata_events e ON e.deviceId = se.eventDeviceId AND e.sequence = se.eventSequence " +
            "WHERE se.sourcePath IN (:paths)",
    )
    suspend fun eventsOfSourcesChunk(paths: List<String>): List<SourceEventJson>

    /** The stored events of each source in [paths], in one join per bind-limit slice. */
    suspend fun eventsOfSources(paths: List<String>): List<SourceEventJson> =
        inBindChunks(paths) { eventsOfSourcesChunk(it) }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceEvents(events: List<MetadataSourceEventEntity>)

    @Query("SELECT * FROM metadata_transaction_actors WHERE actorId = :actorId")
    suspend fun actor(actorId: String): MetadataTransactionActorEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActor(actor: MetadataTransactionActorEntity)

    @Query("SELECT * FROM metadata_projection_state WHERE id = 1")
    suspend fun state(): MetadataProjectionStateEntity?

    @Upsert
    suspend fun upsertState(state: MetadataProjectionStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSuperseded(rows: List<MetadataSupersededEntity>)

    @Query("DELETE FROM metadata_superseded WHERE snapshotPath = :snapshotPath")
    suspend fun deleteSupersededBy(snapshotPath: String)

    /** Content hashes superseded by any CURRENTLY-APPLIED snapshot source. */
    @Query(
        "SELECT DISTINCT s.supersededHash FROM metadata_superseded s " +
            "JOIN metadata_sources src ON src.path = s.snapshotPath " +
            "WHERE src.status = '$SOURCE_APPLIED'",
    )
    suspend fun appliedSupersededHashes(): List<String>

    @Query("DELETE FROM metadata_superseded")
    suspend fun clearSuperseded()

    @Query("DELETE FROM metadata_source_events")
    suspend fun clearSourceEvents()

    @Query("DELETE FROM metadata_transaction_actors")
    suspend fun clearActors()

    @Query("DELETE FROM metadata_events")
    suspend fun clearEvents()

    @Query("DELETE FROM metadata_sources")
    suspend fun clearSources()

    @Transaction
    suspend fun clearAccumulator() {
        clearSourceEvents()
        clearActors()
        clearEvents()
        clearSources()
        clearSuperseded()
    }

    /**
     * Ingest a compaction snapshot source: record its events/provenance like any source, then
     * record its supersession facts. Idempotent — two overlapping snapshots each record their own
     * (path, hash) rows and share event identities harmlessly. Returns the events new to the table,
     * as [ingestSource] does.
     */
    @Transaction
    suspend fun ingestSnapshot(
        source: MetadataSourceEntity,
        events: List<MetadataEventEntity>,
        supersedes: List<String>,
    ): List<MetadataEventEntity> {
        val existing = source(source.path)
        if (existing?.fingerprint == source.fingerprint && existing.status == SourceStatus.APPLIED) return emptyList()
        val inserted = insertNewEvents(events)
        replaceSourceEvents(source.path, events)
        deleteSupersededBy(source.path)
        insertSuperseded(supersedes.map { MetadataSupersededEntity(source.path, it) })
        upsertSource(source.copy(status = SourceStatus.APPLIED))
        return inserted
    }

    /**
     * Record [source] as applied with [events]. Returns the events new to the table, which are the
     * ones a fold has not seen yet; empty when the source was already applied unchanged.
     */
    @Transaction
    suspend fun ingestSource(
        source: MetadataSourceEntity,
        events: List<MetadataEventEntity>,
        transactionActor: MetadataTransactionActorEntity? = null,
    ): List<MetadataEventEntity> {
        val existingSource = source(source.path)
        if (existingSource?.fingerprint == source.fingerprint && existingSource.status == SourceStatus.APPLIED) {
            return emptyList()
        }
        if (transactionActor != null) {
            val existingActor = actor(transactionActor.actorId)
            validateActor(existingActor, transactionActor)
            insertActor(transactionActor)
        }
        val inserted = insertNewEvents(events)
        replaceSourceEvents(source.path, events)
        upsertSource(source.copy(status = SourceStatus.APPLIED))
        return inserted
    }

    /**
     * Insert those of [events] the table does not hold yet, after checking that none collides with
     * an event it does hold. Returns the ones inserted.
     */
    @Transaction
    suspend fun insertNewEvents(events: List<MetadataEventEntity>): List<MetadataEventEntity> {
        val inserted = ArrayList<MetadataEventEntity>()
        for (incoming in events) {
            validateIdentity(event(incoming.deviceId, incoming.sequence), incoming)
            validateHlc(eventAtHlc(incoming.hlcPhysical, incoming.hlcCounter, incoming.hlcDevice), incoming)
            if (insertEvent(incoming) != IGNORED) inserted += incoming
        }
        return inserted
    }

    /**
     * Point the source at [path] to [events], and drop the events it referenced before that no
     * source references any more. Only a source read again with other contents (a grown legacy
     * segment, a re-recorded snapshot) has such events, so the cleanup looks at those alone and a
     * commit never walks the event table.
     */
    @Transaction
    suspend fun replaceSourceEvents(
        path: String,
        events: List<MetadataEventEntity>,
    ) {
        val previous = sourceEvents(path)
        deleteSourceEvents(path)
        insertSourceEvents(events.map { MetadataSourceEventEntity(path, it.deviceId, it.sequence) })
        if (previous.isEmpty()) return
        val kept = events.mapTo(HashSet()) { it.deviceId to it.sequence }
        for (ref in previous) {
            if ((ref.eventDeviceId to ref.eventSequence) !in kept) {
                deleteEventIfUnreferenced(ref.eventDeviceId, ref.eventSequence)
            }
        }
    }

    companion object {
        /** What [insertEvent] returns for an event the table already holds. */
        private const val IGNORED = -1L
    }
}
