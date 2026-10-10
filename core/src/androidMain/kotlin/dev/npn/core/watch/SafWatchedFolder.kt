package dev.npn.core.watch

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.npn.core.fs.ExternalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

/**
 * Android [WatchedFolder] over a SAF tree uri (persistable permission must already be taken).
 * SAF has no file events, so [changes] is empty and imports rely on the importer's poll plus the
 * periodic WorkManager sweep. Listing uses [DocumentFile] — one binder call per child is fine
 * for a scanner-output folder that holds a handful of files.
 */
class SafWatchedFolder(
    context: Context,
    private val treeUri: Uri,
) : WatchedFolder {
    private val appContext = context.applicationContext

    override suspend fun listPdfs(): List<WatchedFolderFile> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(appContext, treeUri) ?: return@withContext emptyList()
            runCatching {
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
            }.getOrDefault(emptyList())
        }

    override suspend fun delete(file: WatchedFolderFile): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // A provider that reports no mtime (lastModified 0) is judged by the size alone.
                val doc = DocumentFile.fromSingleUri(appContext, Uri.parse(file.id)) ?: return@runCatching false
                file.unchangedAt(doc.length(), doc.lastModified()) && doc.delete()
            }.getOrDefault(false)
        }

    override fun changes(): Flow<Unit> = emptyFlow()

    private fun isPdf(file: DocumentFile): Boolean =
        file.type == "application/pdf" || (file.name?.endsWith(".pdf", ignoreCase = true) == true)
}
