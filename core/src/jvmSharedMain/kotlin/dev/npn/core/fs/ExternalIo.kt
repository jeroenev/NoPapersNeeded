package dev.npn.core.fs

import java.io.InputStream
import java.nio.file.Path

/**
 * How [JvmLibraryFileSystem] reads an [ExternalSource] handed to [LibraryFileSystem.importExternal].
 * The library itself is always a plain directory, but what "external" means differs per platform:
 * a desktop file dialog yields a path, an Android share sheet or document picker yields a
 * `content://` uri that only a `ContentResolver` can open. Keeping that behind this seam is what
 * lets the one direct-filesystem implementation serve both platforms.
 */
interface ExternalIo {
    fun openInput(source: ExternalSource): InputStream
}

/** Wrap a local filesystem [path] as the platform's [ExternalSource]. */
expect fun externalSourceOfPath(path: Path): ExternalSource
