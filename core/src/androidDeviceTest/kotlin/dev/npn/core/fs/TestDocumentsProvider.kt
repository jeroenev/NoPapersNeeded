package dev.npn.core.fs

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Controllable real [DocumentsProvider] used by the SAF device tests. */
class TestDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: ROOT_COLUMNS
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        columns.forEach { row.add(it, rootValue(it)) }
        return cursor
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor =
        synchronized(lock) {
            val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
            documentCursor(projection, listOf(node))
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor =
        synchronized(lock) {
            documentCursor(projection, nodes.values.filter { it.parentId == parentDocumentId })
        }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean =
        synchronized(lock) {
            var current = nodes[documentId]
            while (current != null) {
                if (current.id == parentDocumentId) return@synchronized true
                current = current.parentId?.let(nodes::get)
            }
            false
        }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String =
        synchronized(lock) {
            requireNode(parentDocumentId)
            if (raceOnCreateName == displayName) {
                raceOnCreateName = null
                addNode(parentDocumentId, displayName, mimeType, FOREIGN_BYTES)
            }
            val actualName = deduplicatedName(parentDocumentId, displayName)
            addNode(parentDocumentId, actualName, mimeType, byteArrayOf()).id
        }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String =
        synchronized(lock) {
            val node = requireNode(documentId)
            if (raceOnRenameName == displayName) {
                raceOnRenameName = null
                addNode(node.parentId ?: ROOT_ID, displayName, node.mime, FOREIGN_BYTES)
            }
            node.name = deduplicatedName(node.parentId, displayName, excludingId = documentId)
            if (failRenameAfterLanding) {
                failRenameAfterLanding = false
                throw IOException("Rename landed before the provider reported failure")
            }
            documentId
        }

    override fun deleteDocument(documentId: String) {
        synchronized(lock) {
            requireNode(documentId)
            val descendants = mutableSetOf(documentId)
            var changed: Boolean
            do {
                changed = descendants.addAll(nodes.values.filter { it.parentId in descendants }.map { it.id })
            } while (changed)
            descendants.forEach(nodes::remove)
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        synchronized(lock) { requireNode(documentId) }
        return if (mode.contains('w')) writePipe(documentId) else readPipe(documentId)
    }

    private fun readPipe(documentId: String): ParcelFileDescriptor {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        val bytes = synchronized(lock) { requireNode(documentId).bytes.copyOf() }
        thread(name = "test-provider-read", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { it.write(bytes) }
            } catch (_: IOException) {
                // A rejected or interrupted copy closes its reader before this producer finishes.
            }
        }
        return readSide
    }

    private fun writePipe(documentId: String): ParcelFileDescriptor {
        val (readSide, writeSide) = ParcelFileDescriptor.createReliablePipe()
        val completed = CountDownLatch(1)
        val failAfter =
            synchronized(lock) {
                requireNode(documentId).writeCompleted = completed
                failWritesAfterBytes
            }
        thread(name = "test-provider-write", isDaemon = true) {
            val collected = ByteArrayOutputStream()
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        collected.write(buffer, 0, read)
                        synchronized(lock) { requireNode(documentId).bytes = collected.toByteArray() }
                        if (failAfter != null && collected.size() >= failAfter) {
                            readSide.closeWithError("Injected write failure")
                            return@thread
                        }
                    }
                }
            } finally {
                synchronized(lock) {
                    nodes[documentId]?.bytes = collected.toByteArray()
                }
                completed.countDown()
            }
        }
        return writeSide
    }

    companion object {
        const val AUTHORITY = "dev.npn.core.test.documents"
        const val ROOT_ID = "root"

        private val lock = Any()
        private val nodes = linkedMapOf(ROOT_ID to Node(ROOT_ID, null, "", Document.MIME_TYPE_DIR, byteArrayOf()))
        private val ROOT_COLUMNS =
            arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        private val DOCUMENT_COLUMNS =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
            )
        private val FOREIGN_BYTES = "foreign".encodeToByteArray()
        private const val COPY_BUFFER_SIZE = 8 * 1024

        var raceOnCreateName: String? = null
        var raceOnRenameName: String? = null
        var failWritesAfterBytes: Int? = null
        var failRenameAfterLanding = false

        fun reset() {
            synchronized(lock) {
                nodes.clear()
                nodes[ROOT_ID] = Node(ROOT_ID, null, "", Document.MIME_TYPE_DIR, byteArrayOf())
                raceOnCreateName = null
                raceOnRenameName = null
                failWritesAfterBytes = null
                failRenameAfterLanding = false
            }
        }

        fun addFile(
            parentId: String = ROOT_ID,
            name: String,
            bytes: ByteArray,
            mime: String = "application/pdf",
        ): String = synchronized(lock) { addNode(parentId, name, mime, bytes).id }

        /** Rename the document called [name] as another app would; like a cloud provider's, its id stays. */
        fun renameExternally(
            name: String,
            newName: String,
        ) {
            synchronized(lock) { nodes.values.first { it.name == name }.name = newName }
        }

        fun idForName(name: String): String? = synchronized(lock) { nodes.values.firstOrNull { it.name == name }?.id }

        fun bytesForName(name: String): ByteArray? =
            synchronized(lock) {
                nodes.values
                    .firstOrNull { it.name == name }
                    ?.bytes
                    ?.copyOf()
            }

        fun mimeForName(name: String): String? =
            synchronized(lock) { nodes.values.firstOrNull { it.name == name }?.mime }

        fun awaitWrite(name: String) {
            val completed = synchronized(lock) { nodes.values.firstOrNull { it.name == name }?.writeCompleted }
            checkNotNull(completed) { "No provider node recorded a write: $name" }
            check(completed.await(5, TimeUnit.SECONDS)) { "Timed out waiting for provider write: $name" }
        }

        private fun addNode(
            parentId: String?,
            name: String,
            mime: String,
            bytes: ByteArray,
        ): Node {
            val node = Node(UUID.randomUUID().toString(), parentId, name, mime, bytes.copyOf())
            nodes[node.id] = node
            return node
        }

        private fun requireNode(id: String): Node = nodes[id] ?: throw FileNotFoundException(id)

        private fun deduplicatedName(
            parentId: String?,
            requested: String,
            excludingId: String? = null,
        ): String {
            val names =
                nodes.values.filter { it.parentId == parentId && it.id != excludingId }.mapTo(
                    mutableSetOf(),
                ) { it.name }
            if (requested !in names) return requested
            val dot = requested.lastIndexOf('.')
            val base = if (dot > 0) requested.substring(0, dot) else requested
            val extension = if (dot > 0) requested.substring(dot) else ""
            var suffix = 1
            while ("$base ($suffix)$extension" in names) suffix++
            return "$base ($suffix)$extension"
        }

        private fun documentCursor(
            projection: Array<out String>?,
            values: List<Node>,
        ): Cursor {
            val columns = projection ?: DOCUMENT_COLUMNS
            val cursor = MatrixCursor(columns)
            values.forEach { node ->
                val row = cursor.newRow()
                columns.forEach { row.add(it, documentValue(node, it)) }
            }
            return cursor
        }

        private fun rootValue(column: String): Any? =
            when (column) {
                Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                Root.COLUMN_TITLE -> "NPN SAF test provider"
                Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE
                else -> null
            }

        private fun documentValue(
            node: Node,
            column: String,
        ): Any? =
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> node.id
                Document.COLUMN_DISPLAY_NAME -> node.name
                Document.COLUMN_MIME_TYPE -> node.mime
                Document.COLUMN_SIZE -> node.bytes.size
                Document.COLUMN_LAST_MODIFIED -> 1L
                else -> null
            }
    }

    private data class Node(
        val id: String,
        val parentId: String?,
        var name: String,
        val mime: String,
        var bytes: ByteArray,
        var writeCompleted: CountDownLatch = CountDownLatch(0),
    )
}
