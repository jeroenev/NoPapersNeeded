package dev.npn.core.watch

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Android [WatchedFolder] over a SAF tree uri (persistable permission must already be taken).
 * SAF has no file events, so [changes] is empty and imports rely on the importer's poll plus the
 * periodic WorkManager sweep. Listing uses [DocumentFile] — one binder call per child is fine
 * for a scanner-output folder that holds a handful of files.
 */
internal class SafWatchedFolder(
    context: Context,
    private val treeUri: Uri,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WatchedFolder {
    private val appContext = context.applicationContext

    override suspend fun listPdfs(): List<WatchedFolderFile> =
        withContext(io) {
            val root = DocumentFile.fromTreeUri(appContext, treeUri) ?: return@withContext emptyList()
            root
                .listFiles()
                .filter { it.isFile && isPdf(it) }
                .map { child ->
                    WatchedFolderFile(
                        id = child.uri.toString(),
                        name = child.name ?: "document.pdf",
                        size = child.length(),
                        mtimeMillis = child.lastModified(),
                        source = ExternalSource(child.uri),
                    )
                }
        }

    override suspend fun delete(
        file: WatchedFolderFile,
        docId: String,
        libraryHolds: suspend () -> Boolean,
    ): Boolean =
        withContext(io) {
            val uri = file.id.toUri()
            // Gone already, by another hand: nothing to delete.
            val doc = DocumentFile.fromSingleUri(appContext, uri)?.takeIf { it.exists() } ?: return@withContext false
            // A provider that reports no mtime (lastModified 0) has the file read and hashed.
            val holds =
                file.stillHolds(docId, doc.length(), doc.lastModified()) {
                    val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
                    input.use { it.sha256Hex() }
                }
            holds && libraryHolds() && (doc.delete() || throw IOException("Could not delete $uri"))
        }

    override fun changes(): Flow<Unit> = emptyFlow()

    private fun isPdf(file: DocumentFile): Boolean =
        file.type == "application/pdf" || file.name?.let(NpnPaths::isPdf) == true
}
