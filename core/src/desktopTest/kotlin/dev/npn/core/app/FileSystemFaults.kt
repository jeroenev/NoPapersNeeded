package dev.npn.core.app

import dev.npn.core.fs.LibraryFileSystem
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** One failure at a storage boundary, including a call that lands on disk before reporting failure. */
class FileSystemFaults {
    enum class Point { BEFORE_PUBLISH, AFTER_PUBLISH, BEFORE_MOVE, AFTER_MOVE, DELETE }

    private data class Failure(
        val point: Point,
        val path: String?,
    )

    private val armed = AtomicReference<Failure?>()

    fun failNext(
        point: Point,
        path: String? = null,
    ) {
        check(armed.compareAndSet(null, Failure(point, path))) { "A filesystem failure is already armed" }
    }

    fun over(fs: LibraryFileSystem): LibraryFileSystem =
        object : LibraryFileSystem by fs {
            override suspend fun publishVerifiedObjectOnce(
                stagingPath: String,
                canonicalPath: String,
                expectedHash: String,
            ) {
                failIfArmed(Point.BEFORE_PUBLISH, canonicalPath)
                fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
                failIfArmed(Point.AFTER_PUBLISH, canonicalPath)
            }

            override suspend fun move(
                fromRelPath: String,
                toRelPath: String,
            ) {
                failIfArmed(Point.BEFORE_MOVE, toRelPath)
                fs.move(fromRelPath, toRelPath)
                failIfArmed(Point.AFTER_MOVE, toRelPath)
            }

            override suspend fun delete(relPath: String) {
                failIfArmed(Point.DELETE, relPath)
                fs.delete(relPath)
            }
        }

    private fun failIfArmed(
        point: Point,
        path: String,
    ) {
        val failure = armed.get() ?: return
        if (failure.point != point || (failure.path != null && failure.path != path)) return
        if (armed.compareAndSet(failure, null)) {
            throw IOException("Injected $point failure at $path")
        }
    }
}
