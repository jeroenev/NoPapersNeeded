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

    @Query(
        "DELETE FROM metadata_events WHERE NOT EXISTS (" +
            "SELECT 1 FROM metadata_source_events " +
            "WHERE eventDeviceId = metadata_events.deviceId AND eventSequence = metadata_events.sequence" +
            ")",
    )
    suspend fun deleteUnreferencedEvents()

    @Query("SELECT * FROM metadata_source_events WHERE sourcePath = :sourcePath")
    suspend fun sourceEvents(sourcePath: String): List<MetadataSourceEventEntity>

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
            "WHERE src.status = 'applied'",
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
     * (path, hash) rows and share event identities harmlessly.
     */
    @Transaction
    suspend fun ingestSnapshot(
        source: MetadataSourceEntity,
        events: List<MetadataEventEntity>,
        supersedes: List<String>,
    ): Boolean {
        val existing = source(source.path)
        if (existing?.fingerprint == source.fingerprint && existing.status == STATUS_APPLIED) return false
        for (incoming in events) {
            val byIdentity = event(incoming.deviceId, incoming.sequence)
            validateIdentity(byIdentity, incoming)
            val byHlc = eventAtHlc(incoming.hlcPhysical, incoming.hlcCounter, incoming.hlcDevice)
            validateHlc(byHlc, incoming)
            insertEvent(incoming)
        }
        deleteSourceEvents(source.path)
        insertSourceEvents(events.map { MetadataSourceEventEntity(source.path, it.deviceId, it.sequence) })
        deleteSupersededBy(source.path)
        insertSuperseded(supersedes.map { MetadataSupersededEntity(source.path, it) })
        deleteUnreferencedEvents()
        upsertSource(source.copy(status = STATUS_APPLIED))
        return true
    }

    @Transaction
    suspend fun ingestSource(
        source: MetadataSourceEntity,
        events: List<MetadataEventEntity>,
        transactionActor: MetadataTransactionActorEntity? = null,
    ): Boolean {
        val existingSource = source(source.path)
        if (existingSource?.fingerprint == source.fingerprint && existingSource.status == STATUS_APPLIED) {
            return false
        }
        if (transactionActor != null) {
            val existingActor = actor(transactionActor.actorId)
            validateActor(existingActor, transactionActor)
            insertActor(transactionActor)
        }
        for (incoming in events) {
            val byIdentity = event(incoming.deviceId, incoming.sequence)
            validateIdentity(byIdentity, incoming)
            val byHlc = eventAtHlc(incoming.hlcPhysical, incoming.hlcCounter, incoming.hlcDevice)
            validateHlc(byHlc, incoming)
            insertEvent(incoming)
        }
        deleteSourceEvents(source.path)
        insertSourceEvents(
            events.map { MetadataSourceEventEntity(source.path, it.deviceId, it.sequence) },
        )
        deleteUnreferencedEvents()
        upsertSource(source.copy(status = STATUS_APPLIED))
        return true
    }

    companion object {
        const val STATUS_APPLIED = "applied"
        const val STATUS_RETRYABLE = "retryable"
    }
}
