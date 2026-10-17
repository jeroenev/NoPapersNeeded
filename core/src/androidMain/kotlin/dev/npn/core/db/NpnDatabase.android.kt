package dev.npn.core.db

import android.content.Context
import androidx.room3.Room

/**
 * Build the local index database on Android, in the app's databases directory under [name] (never
 * inside the synced library folder); each opened library has its own, named after a stable hash of
 * its location. Destructive migration is the intended fallback: the cache is rebuildable from files
 * and journals. This device's own state is in [deviceDatabase], which has none ([NpnDatabase.configure]).
 */
fun npnDatabase(
    context: Context,
    name: String,
): NpnDatabase =
    NpnDatabase
        .configure(
            Room.databaseBuilder<NpnDatabase>(
                context = context.applicationContext,
                name = context.getDatabasePath(name).absolutePath,
            ),
        ).build()
