package dev.npn.core.watch

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.externalSourceOfPath
import dev.npn.core.fs.sha256Hex
import dev.npn.core.fs.watchDirs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.attribute.BasicFileAttributes
import kotlin.streams.asSequence

/**
 * Plain-directory [WatchedFolder] (desktop, and Android's direct storage mode): listed
 * non-recursively via java.nio and watched through [watchDirs] on the single directory, for new
 * and rewritten files only.
 */
class JvmWatchedFolder(
    private val dir: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WatchedFolder {
    override suspend fun listPdfs(): List<WatchedFolderFile> =
        withContext(io) {
            if (!Files.isDirectory(dir)) return@withContext emptyList()
            Files.list(dir).use { stream ->
                stream
                    .asSequence()
                    .filter {
                        Files.isRegularFile(it) && NpnPaths.isPdf(it.fileName.toString())
                    }.map { path ->
                        // A file removed since it was listed: 0 is skipped as possibly still being written.
                        val mtime =
                            runCatching {
                                Files.getLastModifiedTime(path).toMillis()
                            }.getOrDefault(0L)
                        WatchedFolderFile(
                            id = path.toAbsolutePath().toString(),
                            name = path.fileName.toString(),
                            size = runCatching { Files.size(path) }.getOrDefault(0L),
                            mtimeMillis = mtime,
                            source = externalSourceOfPath(path),
                        )
                    }.toList()
            }
        }

    override suspend fun delete(
        file: WatchedFolderFile,
        docId: String,
        libraryHolds: suspend () -> Boolean,
    ): Boolean =
        withContext(io) {
            // Paths.get, not Path.of: the latter is Java 11 and only reached Android at API 34.
            val path = Paths.get(file.id)
            // Gone already, by another hand: nothing to delete.
            val attrs =
                try {
                    Files.readAttributes(path, BasicFileAttributes::class.java)
                } catch (_: NoSuchFileException) {
                    return@withContext false
                }
            val holds =
                file.stillHolds(docId, attrs.size(), attrs.lastModifiedTime().toMillis()) {
                    Files.newInputStream(path).use { it.sha256Hex() }
                }
            holds && libraryHolds() && Files.deleteIfExists(path)
        }

    override fun changes(): Flow<Unit> = watchDirs(dir, recursive = false, kinds = NEW_OR_REWRITTEN).map { }

    private companion object {
        /** What the folder is watched for: new and rewritten files, not deleted ones. */
        val NEW_OR_REWRITTEN = arrayOf(ENTRY_CREATE, ENTRY_MODIFY)
    }
}
