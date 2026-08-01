package dev.npn.core.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Build the local index database on desktop. The caller decides the location (must be
 * app-local storage, never inside the synced library folder). Destructive migration is the
 * intended fallback because the cache is rebuildable from files and journals.
 */
fun npnDatabase(file: File): NpnDatabase {
    file.parentFile?.mkdirs()
    return Room
        .databaseBuilder<NpnDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
