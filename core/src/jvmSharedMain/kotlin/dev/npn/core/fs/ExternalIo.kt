package dev.npn.core.fs

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/**
 * How [JvmLibraryFileSystem] reads an [ExternalSource] handed to [LibraryFileSystem.importExternal]
 * and writes one handed to [LibraryFileSystem.copyOut]. The library itself is always a plain
 * directory, but what "external" means differs per platform: a desktop file dialog yields a path,
 * an Android share sheet or document picker yields a `content://` uri that only a
 * `ContentResolver` can open. Keeping that behind this seam is what lets the one direct-filesystem
 * implementation serve both platforms.
 */
interface ExternalIo {
    fun openInput(source: ExternalSource): InputStream

    /** Open [target] for writing from the start; an existing target is truncated, never appended to. */
    fun openOutput(target: ExternalSource): OutputStream
}

/**
 * The platform's plain-file [ExternalIo]: desktop paths, or on Android `file://` uris only.
 * Android callers that import from content uris pass a resolver-backed implementation instead.
 */
expect fun defaultExternalIo(): ExternalIo

/** Wrap a local filesystem [path] as the platform's [ExternalSource]. */
expect fun externalSourceOfPath(path: Path): ExternalSource
