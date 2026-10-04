package dev.npn.core.fs

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/** Exercises the packaged native bridge on the device's real shared-storage filesystem. */
class DirectStorageDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: Path
    private lateinit var fs: JvmLibraryFileSystem

    @Before
    fun setup() {
        root = Files.createTempDirectory(checkNotNull(context.getExternalFilesDir(null)).toPath(), "npn-direct-test-")
        fs =
            JvmLibraryFileSystem(
                root,
                ContentResolverExternalIo(context),
                NoReplaceLinker { _, _ -> false },
            )
    }

    @After
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun sharedStoragePublishesCompleteObjectsAndDeduplicates() =
        runBlocking {
            val bytes = "transaction".encodeToByteArray()
            val hash = sha256Bytes(bytes)
            fs.writeBytesOnce("bucket/first.part", bytes)
            assertEquals(
                VerifiedPublicationResult.PUBLISHED,
                fs.publishVerifiedObjectOnce("bucket/first.part", "bucket/object", hash),
            )
            fs.writeBytesOnce("bucket/second.part", bytes)
            assertEquals(
                VerifiedPublicationResult.ALREADY_PRESENT,
                fs.publishVerifiedObjectOnce("bucket/second.part", "bucket/object", hash),
            )
            assertArrayEquals(bytes, fs.readBytes("bucket/object"))
            assertFalse(fs.exists("bucket/first.part"))
            assertFalse(fs.exists("bucket/second.part"))
        }

    @Test
    fun sharedStorageRenameRefusesForeignTarget() {
        val source = Files.write(root.resolve("source"), "ours".encodeToByteArray())
        val target = Files.write(root.resolve("target"), "synced".encodeToByteArray())
        assertThrows(FileAlreadyExistsException::class.java) { NoReplaceMove.move(source, target) }
        assertEquals("synced", String(Files.readAllBytes(target)))
        assertTrue(Files.exists(source))
    }
}
