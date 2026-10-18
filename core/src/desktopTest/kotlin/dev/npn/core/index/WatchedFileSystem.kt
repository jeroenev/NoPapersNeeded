package dev.npn.core.index

import androidx.room3.useWriterConnection
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem

/**
 * A [LibraryFileSystem] over a real one that records every call it makes and whether the calling
 * coroutine held an open index transaction at the time.
 */
internal class WatchedFileSystem(
    private val delegate: LibraryFileSystem,
) : LibraryFileSystem by delegate {
    lateinit var db: NpnDatabase
    private val calls = ArrayList<String>()
    private val inTransaction = ArrayList<String>()

    fun calls(name: String): List<String> = synchronized(calls) { calls.filter { it.startsWith("$name ") } }

    /** Every call made while a transaction was open. */
    fun callsInsideTransactions(): List<String> = synchronized(calls) { inTransaction.toList() }

    fun clear() =
        synchronized(calls) {
            calls.clear()
            inTransaction.clear()
        }

    private suspend fun record(
        name: String,
        path: String,
    ) {
        val open = db.useWriterConnection { it.inTransaction() }
        synchronized(calls) {
            calls += "$name $path"
            if (open) inTransaction += "$name $path"
        }
    }

    override suspend fun listAllIndexable(): List<FileMeta> = delegate.listAllIndexable().also { record("list", "") }

    override suspend fun listAllUnder(relPath: String): List<FileMeta> {
        record("list", relPath)
        return delegate.listAllUnder(relPath)
    }

    override suspend fun exists(relPath: String): Boolean {
        record("exists", relPath)
        return delegate.exists(relPath)
    }

    override suspend fun stat(relPath: String): FileMeta? {
        record("stat", relPath)
        return delegate.stat(relPath)
    }

    override suspend fun readBytes(relPath: String): ByteArray {
        record("read", relPath)
        return delegate.readBytes(relPath)
    }

    override suspend fun sha256(relPath: String): String {
        record("sha256", relPath)
        return delegate.sha256(relPath)
    }

    override suspend fun writeBytesOnce(
        relPath: String,
        bytes: ByteArray,
    ) {
        record("write", relPath)
        delegate.writeBytesOnce(relPath, bytes)
    }

    override suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    ) {
        record("publish", canonicalPath)
        delegate.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
    }

    override suspend fun claimDocument(
        fromRelPath: String,
        toRelPath: String,
    ) {
        record("claim", fromRelPath)
        delegate.claimDocument(fromRelPath, toRelPath)
    }

    override suspend fun copyOnce(
        fromRelPath: String,
        toRelPath: String,
    ) {
        record("copy", fromRelPath)
        delegate.copyOnce(fromRelPath, toRelPath)
    }

    override suspend fun move(
        fromRelPath: String,
        toRelPath: String,
    ) {
        record("move", fromRelPath)
        delegate.move(fromRelPath, toRelPath)
    }

    override suspend fun delete(relPath: String) {
        record("delete", relPath)
        delegate.delete(relPath)
    }

    override suspend fun importExternal(
        source: ExternalSource,
        toRelPath: String,
    ) {
        record("import", toRelPath)
        delegate.importExternal(source, toRelPath)
    }
}
