package dev.npn.core.fs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes

/**
 * What changes under [root], from a [WatchService]: the path of each change of the [kinds] asked
 * for, or null when the watcher lost track (an overflow). With [recursive] every directory below
 * [root] is watched, including those created later; otherwise [root] alone.
 *
 * Registering walks the tree, so it runs on [io], like the blocking wait for events, and stops when
 * the collector does. A directory that cannot be watched is skipped. Once nothing is watched the
 * flow goes quiet; it ends only when its collector stops, which closes the watcher.
 */
internal fun watchDirs(
    root: Path,
    recursive: Boolean,
    kinds: Array<WatchEvent.Kind<Path>> = arrayOf(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY),
    io: CoroutineDispatcher = Dispatchers.IO,
): Flow<Path?> =
    callbackFlow {
        val watcher = root.fileSystem.newWatchService()
        launch(io) { DirWatch(watcher, kinds, recursive).run(root) { trySend(it) } }
        awaitClose {
            try {
                watcher.close()
            } catch (_: IOException) {
            }
        }
    }

/** One [watchDirs] collection: the directories [watcher] watches, by their key. */
private class DirWatch(
    private val watcher: WatchService,
    private val kinds: Array<WatchEvent.Kind<Path>>,
    private val recursive: Boolean,
) {
    private val dirs = HashMap<WatchKey, Path>()

    /** Watch [root] and report each change to [emit], until nothing is watched or [watcher] closes. */
    fun run(
        root: Path,
        emit: (Path?) -> Unit,
    ) {
        register(root)
        while (dirs.isNotEmpty()) {
            val key = take() ?: return
            val dir = dirs[key]
            for (event in key.pollEvents()) emit(changed(dir, event))
            if (!key.reset()) dirs.remove(key)
        }
    }

    /**
     * The path [event] in [dir] reports, null when it names none. A directory it reports created
     * below a recursive watch is watched from then on.
     */
    private fun changed(
        dir: Path?,
        event: WatchEvent<*>,
    ): Path? {
        if (event.kind() == OVERFLOW) return null
        val child = (event.context() as? Path)?.let { dir?.resolve(it) } ?: return null
        if (recursive && event.kind() == ENTRY_CREATE && Files.isDirectory(child)) register(child)
        return child
    }

    /** Watch [start], and with [recursive] every directory below it. */
    private fun register(start: Path) {
        if (!recursive) {
            watch(start)
            return
        }
        if (!Files.exists(start)) return
        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult = if (watch(dir)) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }

    /**
     * Watch [dir], skipping one that cannot be watched. False once [watcher] is closed: the collector
     * stopped while directories were still being registered, so there is no point going on.
     */
    private fun watch(dir: Path): Boolean =
        try {
            dirs[dir.register(watcher, kinds)] = dir
            true
        } catch (_: ClosedWatchServiceException) {
            false
        } catch (_: IOException) {
            true
        }

    /** The next key with events, or null once [watcher] is closed or the thread interrupted. */
    private fun take(): WatchKey? =
        try {
            watcher.take()
        } catch (_: InterruptedException) {
            null
        } catch (_: ClosedWatchServiceException) {
            null
        }
}
