package dev.npn.core.sync

import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.externalSourceOfPath
import dev.npn.core.fs.toHexLower
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.io.path.name
import kotlin.uuid.Uuid

/** [LocalStaging] over a plain app-private directory (the desktop tmp dir, Android's cache dir). */
class DirectoryStaging(
    private val dir: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocalStaging {
    override fun newPath(suffix: String): String {
        Files.createDirectories(dir)
        return dir.resolve("$PREFIX${Uuid.random()}$suffix").toAbsolutePath().toString()
    }

    override suspend fun exists(path: String): Boolean = withContext(io) { Files.isRegularFile(Paths.get(path)) }

    override suspend fun size(path: String): Long? =
        withContext(io) {
            val file = Paths.get(path)
            if (Files.isRegularFile(file)) Files.size(file) else null
        }

    override suspend fun sha256(path: String): String =
        withContext(io) {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(Paths.get(path)).use { input ->
                DigestInputStream(input, digest).use { stream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (stream.read(buffer) != -1) {
                        // reading feeds the digest
                    }
                }
            }
            digest.digest().toHexLower()
        }

    override suspend fun delete(path: String) {
        withContext(io) { Files.deleteIfExists(Paths.get(path)) }
    }

    override suspend fun write(
        path: String,
        bytes: ByteArray,
    ) {
        withContext(io) { Files.write(Paths.get(path), bytes, StandardOpenOption.CREATE_NEW) }
    }

    override fun externalSource(path: String): ExternalSource = externalSourceOfPath(Paths.get(path))

    override suspend fun clear() {
        withContext(io) {
            if (!Files.isDirectory(dir)) return@withContext
            Files.list(dir).use { children ->
                children.filter { it.name.startsWith(PREFIX) }.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private companion object {
        const val PREFIX = "npn-sync-"
        const val BUFFER_SIZE = 64 * 1024
    }
}
