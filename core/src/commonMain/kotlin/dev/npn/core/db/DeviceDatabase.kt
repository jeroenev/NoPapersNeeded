package dev.npn.core.db

import androidx.room3.AutoMigration
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * This device's own state for one library: what neither the library folder nor the journal holds,
 * so nothing could rebuild it. It lives next to [NpnDatabase] in app-local storage, is never
 * synced, and, unlike the index, is never dropped: every schema change ships a real migration, and
 * [configure] gives its builders no destructive fallback.
 *
 * - `kv`: the journal device id and the installation it belongs to, and the assistant's chat
 *   sessions and conversations ([KeyValueDao]);
 * - queued AI edit runs ([AiEditJobDao]), the only copy of suggestions already paid for;
 * - the automatic AI enrichment queue, and which documents it already enriched here
 *   ([AiEnrichQueueDao]);
 * - the OCR queue with its requests, attempts and backoff, and the pages a failed run already paid
 *   for ([OcrQueueDao]);
 * - what the cloud sync last established about each file on its remote ([SyncRemoteDao]);
 * - the AI spend ledger, never synced and never carrying a prompt ([AiUsageDao]).
 *
 * Rows are keyed by document hash, which a rebuilt index gives the same documents again.
 */
@Database(
    // v2: adds sync_remote_entries (what the cloud sync engine last knew about each remote file).
    // v3: adds ai_usage (the device-local AI spend ledger).
    // v4: persists queued edit request reservations across restarts and ledger resets.
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = DeviceDatabase.BackfillRequestReservations::class),
    ],
    entities = [
        KeyValueEntity::class,
        AiEditJobEntity::class,
        AiEditJobDocEntity::class,
        AiEnrichQueueEntity::class,
        AiEnrichedEntity::class,
        OcrQueueEntity::class,
        OcrPageCacheEntity::class,
        SyncRemoteEntryEntity::class,
        AiUsageEntity::class,
    ],
)
@ConstructedBy(DeviceDatabaseConstructor::class)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun keyValueDao(): KeyValueDao

    abstract fun aiEditJobDao(): AiEditJobDao

    abstract fun aiEnrichQueueDao(): AiEnrichQueueDao

    abstract fun ocrQueueDao(): OcrQueueDao

    abstract fun syncRemoteDao(): SyncRemoteDao

    abstract fun aiUsageDao(): AiUsageDao

    /** Preserve the calls still visible in the old ledger when upgrading an unfinished run. */
    class BackfillRequestReservations : AutoMigrationSpec {
        override suspend fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL(
                "UPDATE ai_edit_jobs SET requestsUsed = " +
                    "(SELECT COUNT(*) FROM ai_usage WHERE ai_usage.runId = ai_edit_jobs.jobId)",
            )
        }
    }

    companion object {
        /**
         * What every platform's builder sets: the bundled driver and queries on [queryDispatcher].
         * Never a destructive fallback, unlike [NpnDatabase.configure]: what this database holds
         * cannot be rebuilt, so a schema change without a migration fails to open instead.
         */
        fun configure(
            builder: Builder<DeviceDatabase>,
            queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): Builder<DeviceDatabase> = builder.setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(queryDispatcher)
    }
}

/** KSP generates the platform actuals for this constructor; see [NpnDatabaseConstructor]. */
@Suppress("KotlinNoActualForExpect")
expect object DeviceDatabaseConstructor : RoomDatabaseConstructor<DeviceDatabase> {
    override fun initialize(): DeviceDatabase
}
