package dev.npn.core.fs

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class SafLibraryFileSystemDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val treeUri: Uri =
        DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.ROOT_ID,
        )
    private lateinit var fs: SafLibraryFileSystem

    @Before
    fun setup() {
        TestDocumentsProvider.reset()
        fs = SafLibraryFileSystem(context, treeUri)
    }

    @After
    fun teardown() {
        TestDocumentsProvider.reset()
    }

    @Test
    fun importExternalStreamsExactBytes() =
        runBlocking {
            val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
            val sourceId = TestDocumentsProvider.addFile(name = "outside.pdf", bytes = bytes)

            fs.importExternal(ExternalSource(documentUri(sourceId)), "inbox/imported.pdf")

            TestDocumentsProvider.awaitWrite("imported.pdf")
            assertArrayEquals(bytes, TestDocumentsProvider.bytesForName("imported.pdf"))
        }

    @Test
    fun concurrentCreateCollisionLeavesForeignTargetAndRemovesDeduplicatedCopy() =
        runBlocking {
            val sourceId = TestDocumentsProvider.addFile(name = "outside.pdf", bytes = "ours".encodeToByteArray())
            TestDocumentsProvider.raceOnCreateName = "target.pdf"

            val thrown =
                assertThrowsBlocking(IllegalStateException::class.java) {
                    fs.importExternal(ExternalSource(documentUri(sourceId)), "target.pdf")
                }
            assertTrue(
                "expected the exact-name guard to fire, got: ${thrown.message}",
                thrown.message.orEmpty().contains("renamed"),
            )

            assertArrayEquals("foreign".encodeToByteArray(), TestDocumentsProvider.bytesForName("target.pdf"))
            assertNull(TestDocumentsProvider.idForName("target (1).pdf"))
        }

    /**
     * A sync tool creates the directory a write needs between the listing and the create, and the
     * provider names ours "sub (1)": the write goes into the exact-name one, where a fresh filesystem
     * finds it, and no "sub (1)" is left.
     */
    @Test
    fun aDirectoryCreatedUnderAClashIsTheOneWithTheExactName() =
        runBlocking {
            TestDocumentsProvider.raceOnCreateName = "sub"
            val bytes = "ours".encodeToByteArray()

            fs.writeBytesOnce("sub/a.bin", bytes)

            TestDocumentsProvider.awaitWrite("a.bin")
            assertArrayEquals(bytes, SafLibraryFileSystem(context, treeUri).readBytes("sub/a.bin"))
            assertNull(TestDocumentsProvider.idForName("sub (1)"))
        }

    /**
     * Another app renames `.npn` away and makes a new one, and the provider keeps the renamed one's
     * id, as cloud providers do. A publish acknowledged since is at its canonical path in the tree a
     * fresh filesystem lists, not in the renamed one; the write whose staging copy went there fails,
     * and the next one stages afresh.
     */
    @Test
    fun aPublishAfterTheLibraryFolderWasRenamedAwayIsInTheTreeAsItIsNow() =
        runBlocking {
            publish("first".encodeToByteArray(), "first")
            TestDocumentsProvider.renameExternally(NpnPaths.NPN_DIR, ".npn-backup")
            TestDocumentsProvider.addFile(name = NpnPaths.NPN_DIR, bytes = byteArrayOf(), mime = Document.MIME_TYPE_DIR)

            val second = "second".encodeToByteArray()
            try {
                publish(second, "second")
            } catch (_: VerifiedPublicationFailedException) {
                publish(second, "second-again")
            }

            assertArrayEquals(second, SafLibraryFileSystem(context, treeUri).readBytes("$BUCKET/second.jsonl"))
        }

    /** Stage [bytes] under [nonce] in [BUCKET] and publish them as `<content>.jsonl`, as the journal does. */
    private suspend fun publish(
        bytes: ByteArray,
        nonce: String,
    ) {
        val staging = "$BUCKET/$nonce.part"
        fs.writeBytesOnce(staging, bytes)
        TestDocumentsProvider.awaitWrite("$nonce.part")
        fs.publishVerifiedObjectOnce(
            staging,
            "$BUCKET/${bytes.decodeToString()}.jsonl",
            bytes.inputStream().sha256Hex(),
        )
    }

    @Test
    fun interruptedImportRemovesPartialDestination() =
        runBlocking {
            val sourceId = TestDocumentsProvider.addFile(name = "outside.pdf", bytes = ByteArray(128 * 1024))
            TestDocumentsProvider.failWritesAfterBytes = 16 * 1024

            assertThrowsBlocking(IOException::class.java) {
                fs.importExternal(ExternalSource(documentUri(sourceId)), "partial.pdf")
            }

            assertNull(TestDocumentsProvider.idForName("partial.pdf"))
        }

    @Test
    fun fallbackMovePublishesPdfMimeAndDeletesSourceOnlyAfterSuccess() =
        runBlocking {
            val bytes = "pdf bytes".encodeToByteArray()
            TestDocumentsProvider.addFile(name = "source.part", bytes = bytes, mime = "application/pdf")

            fs.move("source.part", "folder/final.pdf")

            TestDocumentsProvider.awaitWrite("final.pdf")
            assertNull(TestDocumentsProvider.idForName("source.part"))
            assertArrayEquals(bytes, TestDocumentsProvider.bytesForName("final.pdf"))
            assertEquals("application/pdf", TestDocumentsProvider.mimeForName("final.pdf"))
        }

    @Test
    fun concurrentMoveCollisionPreservesSourceAndForeignTarget() =
        runBlocking {
            TestDocumentsProvider.addFile(name = "source.part", bytes = "ours".encodeToByteArray())
            TestDocumentsProvider.raceOnRenameName = "final.pdf"

            assertThrowsBlocking(IOException::class.java) {
                fs.move("source.part", "folder/final.pdf")
            }

            assertNotNull(TestDocumentsProvider.idForName("source.part"))
            assertArrayEquals("foreign".encodeToByteArray(), TestDocumentsProvider.bytesForName("final.pdf"))
            assertNull(TestDocumentsProvider.idForName("final (1).pdf"))
        }

    private fun documentUri(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun <T : Throwable> assertThrowsBlocking(
        expected: Class<T>,
        block: suspend () -> Unit,
    ): T = assertThrows(expected) { runBlocking { block() } }

    private companion object {
        const val BUCKET = "${NpnPaths.JOURNAL_DIR}/objects-v2-ab"
    }
}
