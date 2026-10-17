package dev.npn.core.db

import androidx.room3.Room
import java.io.File

/**
 * Build the local index database on desktop. The caller decides the location (must be
 * app-local storage, never inside the synced library folder). Destructive migration is the
 * intended fallback because the cache is rebuildable from files and journals; this device's own
 * state is in [deviceDatabase], which has none ([NpnDatabase.configure]).
 */
fun npnDatabase(file: File): NpnDatabase {
    file.parentFile?.mkdirs()
    return NpnDatabase.configure(Room.databaseBuilder<NpnDatabase>(name = file.absolutePath)).build()
}
