package dev.npn.core.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Build this device's state database for one library on Android, under [name] next to its index.
 * No destructive fallback, unlike the index: what it holds cannot be rebuilt (see [DeviceDatabase]).
 */
fun deviceDatabase(
    context: Context,
    name: String,
    queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
): DeviceDatabase =
    Room
        .databaseBuilder<DeviceDatabase>(
            context = context.applicationContext,
            name = context.getDatabasePath(name).absolutePath,
        ).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryDispatcher)
        .build()
