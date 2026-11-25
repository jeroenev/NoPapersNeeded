package dev.npn.app.platform

import android.content.Intent
import androidx.core.content.FileProvider
import dev.npn.core.fs.NpnPaths
import dev.npn.core.util.SystemEpochClock
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.days

actual suspend fun sharePdfBytes(
    fileName: String,
    bytes: ByteArray,
    io: CoroutineDispatcher,
) {
    val context = FileKit.context.applicationContext
    val file =
        withContext(io) {
            writeShareCopy(File(context.cacheDir, "share"), fileName, bytes, SystemEpochClock.nowMillis())
        }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val share =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    LockActivityHandoff.started()
    try {
        context.startActivity(Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (failure: Exception) {
        LockActivityHandoff.clear()
        throw failure
    }
}

/**
 * How long a shared copy is kept: the app it went to may read it any time after the share sheet
 * closes, and the system may clear the cache before then anyway.
 */
private val SHARED_COPY_LIFETIME = 1.days

/**
 * Writes [bytes] as the copy shared as [fileName], in a new directory of its own under [shareDir]:
 * its URI is derived from its path, so a later share, even of a document with the same name, never
 * writes to a file an earlier one handed out. The file keeps the name the receiving app shows. Every
 * copy (or entry of the share directory) older than [SHARED_COPY_LIFETIME] at [nowMillis] is
 * deleted first.
 */
internal fun writeShareCopy(
    shareDir: File,
    fileName: String,
    bytes: ByteArray,
    nowMillis: Long,
): File {
    shareDir.mkdirs()
    val expired = nowMillis - SHARED_COPY_LIFETIME.inWholeMilliseconds
    shareDir.listFiles()?.filter { it.lastModified() < expired }?.forEach { it.deleteRecursively() }
    val dir = Files.createTempDirectory(shareDir.toPath(), null).toFile()
    return shareFile(dir, fileName).apply { writeBytes(bytes) }
}

/**
 * The file in [dir] a copy shared as [fileName] is written to: [fileName] made one safe file name,
 * so a title never reaches a folder of its own or outside [dir]. A name that still would (a blank
 * one names [dir] itself) throws instead of writing anywhere else.
 */
internal fun shareFile(
    dir: File,
    fileName: String,
): File {
    val file = File(dir, NpnPaths.sanitizeFileName(fileName))
    require(file.canonicalFile.parentFile == dir.canonicalFile) { "Not a file name: $fileName" }
    return file
}
