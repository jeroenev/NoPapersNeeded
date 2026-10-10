package dev.npn.core.watch

import dev.npn.core.fs.externalSourceOfPath
import dev.npn.core.fs.sha256Hex
import dev.npn.core.fs.watchDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import kotlin.streams.asSequence

/**
 * Plain-directory [WatchedFolder] (desktop, and Android's direct storage mode): listed
 * non-recursively via java.nio and watched through [watchDirs] on the single directory, for new
 * and rewritten files only.
 */
class JvmWatchedFolder(
    private val dir: Path,
) : WatchedFolder {
    override suspend fun listPdfs(): List<WatchedFolderFile> =
        withContext(Dispatchers.IO) {
            if (!Files.isDirectory(dir)) return@withContext emptyList()
            runCatching {
                Files.list(dir).use { stream ->
                    stream
                        .asSequence()
                        .filter {
                            Files.isRegularFile(it) &&
                                it.fileName.toString().endsWith(".pdf", ignoreCase = true)
                        }.map { path ->
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
            }.getOrDefault(emptyList())
        }

    override suspend fun delete(
        file: WatchedFolderFile,
        docId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // Paths.get, not Path.of: the latter is Java 11 and only reached Android at API 34.
                val path = Paths.get(file.id)
                val holds =
                    file.stillHolds(docId, Files.size(path), Files.getLastModifiedTime(path).toMillis()) {
                        Files.newInputStream(path).use { it.sha256Hex() }
                    }
                holds && Files.deleteIfExists(path)
            }.getOrDefault(false)
        }

    override fun changes(): Flow<Unit> = watchDirs(dir, recursive = false, kinds = NEW_OR_REWRITTEN).map { }

    private companion object {
        /** What the folder is watched for: new and rewritten files, not deleted ones. */
        val NEW_OR_REWRITTEN = arrayOf(ENTRY_CREATE, ENTRY_MODIFY)
    }
}
