package dev.npn.core.db

import java.io.File
import java.nio.file.Files

/**
 * A library's two databases in a fresh temp directory, each opened when first used: the index
 * ([db], through [openIndex]) and the device database ([deviceDb]). [close] closes what was opened
 * and deletes the directory.
 */
class TempIndexDb(
    prefix: String = "npn-test-db",
    openIndex: (File) -> NpnDatabase = { npnDatabase(it) },
) : AutoCloseable {
    val dir: File = Files.createTempDirectory(prefix).toFile()

    private val index = lazy { openIndex(File(dir, "index.db")) }
    private val device = lazy { deviceDatabase(File(dir, "device.db")) }

    val db: NpnDatabase by index
    val deviceDb: DeviceDatabase by device

    override fun close() {
        if (index.isInitialized()) index.value.close()
        if (device.isInitialized()) device.value.close()
        dir.deleteRecursively()
    }
}
