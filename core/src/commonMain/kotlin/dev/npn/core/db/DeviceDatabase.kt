package dev.npn.core.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
 *   for ([OcrQueueDao]).
 *
 * Rows are keyed by document hash, which a rebuilt index gives the same documents again.
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        KeyValueEntity::class,
        AiEditJobEntity::class,
        AiEditJobDocEntity::class,
        AiEnrichQueueEntity::class,
        AiEnrichedEntity::class,
        OcrQueueEntity::class,
        OcrPageCacheEntity::class,
    ],
)
@ConstructedBy(DeviceDatabaseConstructor::class)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun keyValueDao(): KeyValueDao

    abstract fun aiEditJobDao(): AiEditJobDao

    abstract fun aiEnrichQueueDao(): AiEnrichQueueDao

    abstract fun ocrQueueDao(): OcrQueueDao

    companion object {
        /**
         * What every platform's builder sets: the bundled driver and queries on [Dispatchers.IO].
         * Never a destructive fallback, unlike [NpnDatabase.configure]: what this database holds
         * cannot be rebuilt, so a schema change without a migration fails to open instead.
         */
        fun configure(builder: Builder<DeviceDatabase>): Builder<DeviceDatabase> =
            builder.setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)
    }
}

/** KSP generates the platform actuals for this constructor; see [NpnDatabaseConstructor]. */
@Suppress("KotlinNoActualForExpect")
expect object DeviceDatabaseConstructor : RoomDatabaseConstructor<DeviceDatabase> {
    override fun initialize(): DeviceDatabase
}
