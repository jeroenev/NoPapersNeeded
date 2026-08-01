package dev.npn.core.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Build the local index database on Android. The DB file lives in app-local storage
 * (never inside the synced library folder). Destructive migration is the intended fallback:
 * the cache is rebuildable from files and journals.
 */
fun npnDatabase(context: Context): NpnDatabase = npnDatabase(context, NPN_DATABASE_NAME)

/**
 * Build the index database with an explicit file [name], so each opened library can have its own
 * cache (the service layer suffixes the name with a stable hash of the library location). Additive
 * overload; the no-name variant delegates here with [NPN_DATABASE_NAME].
 */
fun npnDatabase(
    context: Context,
    name: String,
): NpnDatabase {
    val dbFile = context.getDatabasePath(name)
    return Room
        .databaseBuilder<NpnDatabase>(
            context = context.applicationContext,
            name = dbFile.absolutePath,
        ).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
