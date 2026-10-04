package dev.npn.desktop

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One running app per user. Two instances would open the same index databases and drain the same
 * queues, which assumes a single process; they would also overwrite each other's settings. The
 * first instance takes an exclusive lock on a file in the app data directory and holds it until it
 * exits; a second one finds it taken.
 */
internal object SingleInstance {
    // Kept reachable for the life of the process: a collected channel closes and drops the lock.
    // Never released by hand; the OS drops it when the process ends, however it ends.
    private var held: FileLock? = null

    /**
     * Take the lock on [lockFile]; false when another running instance holds it. A filesystem
     * that cannot lock at all (some network home directories) does not keep the app from starting:
     * it runs unguarded, as before this lock existed.
     */
    fun claim(
        lockFile: Path,
        log: (String) -> Unit,
    ): Boolean =
        try {
            Files.createDirectories(lockFile.parent)
            val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = channel.tryLock()
            if (lock == null) channel.close() else held = lock
            lock != null
        } catch (e: IOException) {
            log("cannot lock $lockFile, starting without the single-instance guard: ${e.message}")
            true
        }
}
