package dev.npn.core.db

import androidx.room3.Room
import java.io.File

/**
 * Build this device's state database for one library on desktop, next to its index. No destructive
 * fallback, unlike the index: what it holds cannot be rebuilt (see [DeviceDatabase.configure]).
 */
fun deviceDatabase(file: File): DeviceDatabase {
    file.parentFile?.mkdirs()
    return DeviceDatabase.configure(Room.databaseBuilder<DeviceDatabase>(name = file.absolutePath)).build()
}
