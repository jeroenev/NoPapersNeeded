package dev.npn.core.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Build this device's state database for one library on desktop, next to its index. No destructive
 * fallback, unlike the index: what it holds cannot be rebuilt (see [DeviceDatabase]).
 */
fun deviceDatabase(
    file: File,
    queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
): DeviceDatabase {
    file.parentFile?.mkdirs()
    return Room
        .databaseBuilder<DeviceDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryDispatcher)
        .build()
}
