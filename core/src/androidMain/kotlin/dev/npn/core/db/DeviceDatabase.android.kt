package dev.npn.core.db

import android.content.Context
import androidx.room3.Room

/**
 * Build this device's state database for one library on Android, under [name] next to its index.
 * No destructive fallback, unlike the index: what it holds cannot be rebuilt (see
 * [DeviceDatabase.configure]).
 */
fun deviceDatabase(
    context: Context,
    name: String,
): DeviceDatabase =
    DeviceDatabase
        .configure(
            Room.databaseBuilder<DeviceDatabase>(
                context = context.applicationContext,
                name = context.getDatabasePath(name).absolutePath,
            ),
        ).build()
