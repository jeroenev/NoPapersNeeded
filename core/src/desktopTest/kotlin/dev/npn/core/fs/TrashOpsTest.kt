package dev.npn.core.fs

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class TrashOpsTest {

    private lateinit var root: Path
    private lateinit var fs: JvmLibraryFileSystem
    private lateinit var trash: TrashOps

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("npn-trash-test")
        fs = JvmLibraryFileSystem(root)
        trash = TrashOps(fs)
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun moveToTrashAndRestoreRoundtrip() = runTest {
        fs.writeBytesOnce("inbox/doc.pdf", "content".encodeToByteArray())
        val trashRel = trash.moveToTrash("inbox/doc.pdf", "deadbeefcafebabe")
        trashRel shouldBe ".npn/trash/doc__deadbeef.pdf"
        fs.exists("inbox/doc.pdf").shouldBeFalse()
        fs.exists(trashRel).shouldBeTrue()

        val restored = trash.restore(trashRel, "inbox/doc.pdf")
        restored shouldBe "inbox/doc.pdf"
        fs.readBytes("inbox/doc.pdf").decodeToString() shouldBe "content"
    }

    @Test
    fun moveToTrashAvoidsCollisionWithNumericSuffix() = runTest {
        fs.writeBytesOnce("a/doc.pdf", "1".encodeToByteArray())
        fs.writeBytesOnce("b/doc.pdf", "2".encodeToByteArray())
        // Same name + same hash prefix -> second must get -1.
        val first = trash.moveToTrash("a/doc.pdf", "deadbeefcafebabe")
        val second = trash.moveToTrash("b/doc.pdf", "deadbeefcafebabe")
        first shouldBe ".npn/trash/doc__deadbeef.pdf"
        second shouldBe ".npn/trash/doc__deadbeef-1.pdf"
        trash.listTrash() shouldHaveSize 2
    }

    @Test
    fun restoreUniquifiesWhenTargetTaken() = runTest {
        fs.writeBytesOnce("inbox/doc.pdf", "orig".encodeToByteArray())
        val trashRel = trash.moveToTrash("inbox/doc.pdf", "deadbeefcafebabe")
        // A new file now occupies the original path.
        fs.writeBytesOnce("inbox/doc.pdf", "new".encodeToByteArray())
        val restored = trash.restore(trashRel, "inbox/doc.pdf")
        restored shouldContain "(restored)"
        fs.readBytes(restored).decodeToString() shouldBe "orig"
        fs.readBytes("inbox/doc.pdf").decodeToString() shouldBe "new"
    }

    @Test
    fun purgeOlderThanRemovesOldEntries() = runTest {
        fs.writeBytesOnce("x/old.pdf", "old".encodeToByteArray())
        val oldTrash = trash.moveToTrash("x/old.pdf", "0000000000000000")
        // Force an old mtime.
        val oldPath = root.resolve(oldTrash)
        Files.setLastModifiedTime(oldPath, java.nio.file.attribute.FileTime.fromMillis(1000))

        fs.writeBytesOnce("y/new.pdf", "new".encodeToByteArray())
        trash.moveToTrash("y/new.pdf", "1111111111111111")

        trash.purgeOlderThan(cutoffMillis = 500_000)
        trash.listTrash() shouldHaveSize 1
        fs.exists(oldTrash).shouldBeFalse()
    }
}
