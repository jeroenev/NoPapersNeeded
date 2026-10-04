package dev.npn.core.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Build the local index database on desktop. The caller decides the location (must be
 * app-local storage, never inside the synced library folder). Destructive migration is the
 * intended fallback because the cache is rebuildable from files and journals; this device's own
 * state is in [deviceDatabase], which has none.
 */
fun npnDatabase(
    file: File,
    queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
): NpnDatabase {
    file.parentFile?.mkdirs()
    return Room
        .databaseBuilder<NpnDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryDispatcher)
        .addMigrations(*NpnDatabase.MIGRATIONS)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
