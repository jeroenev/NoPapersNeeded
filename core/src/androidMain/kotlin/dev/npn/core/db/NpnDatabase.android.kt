package dev.npn.core.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Build the local index database on Android, in the app's databases directory under [name] (never
 * inside the synced library folder); each opened library has its own, named after a stable hash of
 * its location. Destructive migration is the intended fallback: the cache is rebuildable from files
 * and journals. This device's own state is in [deviceDatabase], which has none.
 */
fun npnDatabase(
    context: Context,
    name: String,
    queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
): NpnDatabase {
    val dbFile = context.getDatabasePath(name)
    return Room
        .databaseBuilder<NpnDatabase>(
            context = context.applicationContext,
            name = dbFile.absolutePath,
        ).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryDispatcher)
        .let(NpnDatabase::withMigrations)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
