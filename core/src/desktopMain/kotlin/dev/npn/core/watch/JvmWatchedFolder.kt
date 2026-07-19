package dev.npn.core.watch

import dev.npn.core.fs.ExternalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.streams.asSequence

/**
 * Desktop [WatchedFolder]: a plain directory, listed non-recursively via java.nio and watched
 * with a [WatchService] on the single directory (same pattern as JvmLibraryFileSystem.watch,
 * minus the recursive registration).
 */
class JvmWatchedFolder(private val dir: Path) : WatchedFolder {

    override suspend fun listPdfs(): List<WatchedFolderFile> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(dir)) return@withContext emptyList()
        runCatching {
            Files.list(dir).use { stream ->
                stream.asSequence()
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".pdf", ignoreCase = true) }
                    .map { path ->
                        WatchedFolderFile(
                            id = path.toAbsolutePath().toString(),
                            name = path.fileName.toString(),
                            size = runCatching { Files.size(path) }.getOrDefault(0L),
                            mtimeMillis = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L),
                            source = ExternalSource(path),
                        )
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun delete(file: WatchedFolderFile): Boolean = withContext(Dispatchers.IO) {
        runCatching { Files.deleteIfExists(Path.of(file.id)) }.getOrDefault(false)
    }

    override fun changes(): Flow<Unit> = callbackFlow {
        if (!Files.isDirectory(dir)) {
            awaitClose { }
            return@callbackFlow
        }
        val watcher: WatchService = dir.fileSystem.newWatchService()
        try {
            dir.register(
                watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
            )
        } catch (_: IOException) {
            watcher.close()
            awaitClose { }
            return@callbackFlow
        }

        val scope = CoroutineScope(coroutineContext)
        val job = scope.launch(Dispatchers.IO) {
            var key = takeKey(watcher)
            while (isActive && key != null) {
                key.pollEvents()
                trySend(Unit)
                key = if (key.reset()) takeKey(watcher) else null
            }
        }

        awaitClose {
            job.cancel()
            try {
                watcher.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Blocking take, mapped to null when the watcher is interrupted or closed. */
    private fun takeKey(watcher: WatchService): WatchKey? = try {
        watcher.take()
    } catch (_: InterruptedException) {
        null
    } catch (_: ClosedWatchServiceException) {
        null
    }
}
